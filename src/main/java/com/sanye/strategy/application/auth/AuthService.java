package com.sanye.strategy.application.auth;

import com.sanye.strategy.interfaces.auth.dto.LoginDTO;
import com.sanye.strategy.interfaces.auth.dto.MfaChallengeVO;
import com.sanye.strategy.interfaces.auth.dto.MfaVerifyDTO;
import com.sanye.strategy.interfaces.auth.dto.RefreshDTO;
import com.sanye.strategy.interfaces.auth.dto.RegisterDTO;
import com.sanye.strategy.interfaces.auth.dto.TokenVO;
import com.sanye.strategy.infrastructure.redis.ChallengeTokenService;
import com.sanye.strategy.infrastructure.redis.JtiBlacklistService;
import com.sanye.strategy.infrastructure.security.JwtUtil;
import com.sanye.strategy.infrastructure.security.PasswordEncoder;
import com.sanye.strategy.infrastructure.security.TotpUtil;
import com.sanye.strategy.infrastructure.security.UserContext;
import com.sanye.strategy.infrastructure.logging.SecurityEventLogger;
import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.application.device.dto.DeviceInfo;
import com.sanye.strategy.application.device.DeviceService;
import com.sanye.strategy.application.rbac.RbacAuthzService;
import com.sanye.strategy.domain.user.entity.UmsUser;
import com.sanye.strategy.domain.user.entity.UmsUserAccountSecurity;
import com.sanye.strategy.domain.user.entity.UmsUserLoginDevice;
import com.sanye.strategy.domain.user.entity.UmsUserProfile;
import com.sanye.strategy.domain.user.entity.UmsRole;
import com.sanye.strategy.domain.enums.LoginTypeEnum;
import com.sanye.strategy.domain.enums.RegisterChannelEnum;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.enums.UserStatusEnum;
import com.sanye.strategy.domain.enums.YesNoEnum;
import com.sanye.strategy.domain.user.repository.UmsUserAccountSecurityService;
import com.sanye.strategy.domain.user.repository.UmsUserProfileService;
import com.sanye.strategy.domain.user.repository.UmsUserService;
import com.sanye.strategy.domain.rbac.repository.UmsRolePermissionService;
import com.sanye.strategy.domain.user.repository.UmsRoleService;
import com.sanye.strategy.domain.user.repository.UmsUserRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * 认证门面 — 注册/登录/刷新/登出/MFA 二次验证
 * </p>
 * <p>
 * 编排 3 个 base service + {@link DeviceService}（会话行属主），承载跨表事务与防爆破/状态校验规则。
 * 跨表写（注册 3-5、登录 5(成功清零)-7、verifyMfa 5(成功清零)-7）经共享 {@link TransactionTemplate} 单事务，
 * 门面非 {@code AbstractBaseService} 子类、够不到其 {@code doInTransaction} 钩子，故注入框架 Bean。
 * </p>
 * <p>
 * 安全约定：
 * <ul>
 *   <li>登录「用户不存在」与「密码错误」同一提示「账号或密码错误」；401 掩码仅覆盖 缺失用户 vs 密码错误 二分，
 *       已存在账号的状态分支（403 冻结/锁定、410 注销）仍泄露账号存在性——防批量枚举的全局登录限流（批2）未落地，本批不改变该行为。</li>
 *   <li>防爆破：密码/OTP 错误共累计 {@code passwordErrorCount}，达阈值锁 30min（{@code lockTime}）。</li>
 *   <li>userStatus 校验在签发前（冻结 403 / 注销 410）；MFA 开启时登录发 5min 挑战凭证（tempToken）随 403 MFA_REQUIRED 返回，
 *       OTP 通过（verifyMfa）才清计数并发证——密码因子在登录步骤 5 校验，verify 仅验 OTP + 挑战绑定。</li>
 *   <li>refresh 轮换防重放：旧 refresh 一次性作废（写回新哈希）。</li>
 * </ul>
 * </p>
 * <p>
 * 设计说明（门面模式）：
 * <ul>
 *   <li>角色：门面，对外暴露粗粒度认证方法，编排子系统（base service）；Controller 只依赖本门面。</li>
 *   <li>优点：跨表事务与业务规则收口一处，Controller 保持瘦；可单测（mock 子系统与 TransactionTemplate）。</li>
 *   <li>缺点：多一层抽象；门面职责限定认证能力包，不做大杂烩。</li>
 * </ul>
 * </p>
 * <p>
 * 时序（登录，简化）：
 * <pre>
 * AuthController → AuthService.login(dto, ip)
 *   AuthService → userService.getOne(loginType 定列) // 当前 PASSWORD=用户名
 *   AuthService → securityService.getOne(userId)    // 锁校验/计数/MFA
 *   AuthService → passwordEncoder.matches(...)       // 密码校验
 *   AuthService → transactionTemplate.execute {      // 成功路径单事务
 *       securityService.updateById(清计数+锁)
 *       deviceService.createSession(...)             // 会话行
 *       userService.updateById(lastLogin)
 *   }
 *   AuthService → jwtUtil.generateAccessToken(...)   // 签发（事务外）
 * AuthService --> AuthController: TokenVO
 * </pre>
 * </p>
 *
 * @author 31372
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int PASSWORD_ERROR_THRESHOLD = 5;
    private static final int LOCK_MINUTES = 30;
    private static final int REFRESH_TOKEN_TTL_DAYS = 14;
    private static final int CHALLENGE_TTL_SECONDS = 300;
    private static final String DEFAULT_PHONE_COUNTRY_CODE = "+86";
    private static final String INVALID_ACCOUNT_MESSAGE = "账号或密码错误";
    /** 注册默认绑定角色（rbac.sql 内置角色 seed） */
    private static final String DEFAULT_ROLE_CODE = "NORMAL_USER";
    /** 开放注册/登录渠道白名单（当前仅 H5/PC；扩展时改此处，不动 SQL） */
    private static final Set<Integer> OPEN_CHANNEL_CODES =
            Set.of(RegisterChannelEnum.H5.getCode(), RegisterChannelEnum.PC.getCode());
    /** 开放登入方式白名单（当前仅账号密码；SMS/手机号/第三方接口实现后放开） */
    private static final Set<Integer> OPEN_LOGIN_TYPE_CODES =
            Set.of(LoginTypeEnum.PASSWORD.getCode());

    private final UmsUserService userService;
    private final UmsRoleService roleService;
    private final UmsUserRoleService userRoleService;
    private final UmsRolePermissionService rolePermissionService;
    private final UmsUserAccountSecurityService securityService;
    private final UmsUserProfileService profileService;
    private final DeviceService deviceService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TotpUtil totpUtil;
    private final JtiBlacklistService jtiBlacklistService;
    private final ChallengeTokenService challengeTokenService;
    private final TransactionTemplate transactionTemplate;
    private final SecurityEventLogger securityEventLogger;

    // ==================== 注册 ====================

    /**
     * 注册并返回双 Token
     *
     * @param dto      注册参数
     * @param clientIp 客户端 IP
     */
    public TokenVO register(RegisterDTO dto, String clientIp) {
        RegisterChannelEnum channel = validateChannel(dto.getRegisterChannel());
        validatePasswordPolicy(dto.getPassword());
        if (userService.count(new DefaultQueryWrapper<UmsUser>()
                .eq("username", dto.getUsername())) > 0) {
            throw new BizException(ResultCode.CONFLICT, "用户名已被占用");
        }
        String refreshToken = generateRefreshToken();
        UmsUser user = buildRegisterUser(dto, clientIp, channel);
        TokenVO vo = transactionTemplate.execute(status -> {
            userService.insert(user);
            initSecurity(user.getId());
            initProfile(user.getId());
            assignDefaultRole(user.getId());
            // 注册即登录：会话行落渠道 + 登入方式（注册走账号密码）
            UmsUserLoginDevice session = deviceService.createSession(
                    user.getId(), dto.getDeviceInfo(), clientIp, refreshToken, REFRESH_TOKEN_TTL_DAYS,
                    LoginTypeEnum.PASSWORD, channel);
            return issueTokens(user.getId(), loadRoleCodes(user.getId()), loadPermCodes(user.getId()),
                    session.getId(), session.getDeviceId(), refreshToken);
        });
        securityEventLogger.log("authn", dto.getUsername(), clientIp, "REGISTER_OK", "注册成功");
        return vo;
    }

    private UmsUser buildRegisterUser(RegisterDTO dto, String clientIp, RegisterChannelEnum channel) {
        UmsUser user = new UmsUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname() == null || dto.getNickname().isBlank()
                ? dto.getUsername() : dto.getNickname());
        user.setPhone(normalizeNullable(dto.getPhone()));
        user.setEmail(normalizeNullable(dto.getEmail()));
        user.setPhoneCountryCode(DEFAULT_PHONE_COUNTRY_CODE);
        user.setUserStatus(UserStatusEnum.NORMAL);
        user.setRegisterChannel(channel);
        user.setRegisterClientIp(clientIp);
        user.setRegisterDeviceId(dto.getDeviceInfo() == null ? null : dto.getDeviceInfo().getDeviceId());
        user.setIsVip(YesNoEnum.NO);
        return user;
    }

    private void initSecurity(Long userId) {
        UmsUserAccountSecurity security = new UmsUserAccountSecurity();
        security.setUserId(userId);
        security.setPasswordErrorCount(0);
        security.setHasSetPayPassword(YesNoEnum.NO);
        security.setSecretQuestionStatus(YesNoEnum.NO);
        security.setMfaStatus(YesNoEnum.NO);
        securityService.insert(security);
    }

    private void initProfile(Long userId) {
        UmsUserProfile profile = new UmsUserProfile();
        profile.setUserId(userId);
        profileService.insert(profile);
    }

    // ==================== 登录 ====================

    /**
     * 登录（含防爆破/状态校验/MFA 分支）
     */
    public TokenVO login(LoginDTO dto, String clientIp) {
        LoginTypeEnum loginType = validateLoginType(dto.getLoginType());
        RegisterChannelEnum channel = validateChannel(dto.getRegisterChannel());
        UmsUser user = findByAccount(dto.getAccount(), loginType);
        if (user == null) {
            securityEventLogger.log("authn", dto.getAccount(), clientIp, "FAIL", "账号不存在");
            throw new BizException(ResultCode.UNAUTHORIZED, INVALID_ACCOUNT_MESSAGE);
        }
        checkUserStatus(user, clientIp);
        UmsUserAccountSecurity security = loadOrCreateSecurity(user.getId());
        checkLocked(security, user.getId(), clientIp);
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            increaseErrorCount(security, clientIp);
            securityEventLogger.log("authn", dto.getAccount(), clientIp, "FAIL", "密码错误");
            throw new BizException(ResultCode.UNAUTHORIZED, INVALID_ACCOUNT_MESSAGE);
        }
        if (YesNoEnum.YES.equals(security.getMfaStatus())) {
            // MFA 开启：签发 5min 挑战凭证（Redis-only，零 DB 写，不清计数——OTP 通过 verifyMfa 才清）；
            // 登入方式/渠道随挑战绑定携带，verifyMfa 建会话复用
            String tempToken = challengeTokenService.issue(
                    user.getId(), dto.getDeviceInfo().getDeviceId(), loginType, channel, CHALLENGE_TTL_SECONDS);
            MfaChallengeVO challenge = new MfaChallengeVO();
            challenge.setTempToken(tempToken);
            challenge.setExpiresIn(CHALLENGE_TTL_SECONDS);
            securityEventLogger.log("authn", dto.getAccount(), clientIp, "MFA_CHALLENGE", "MFA 挑战签发");
            throw new BizException(ResultCode.MFA_REQUIRED, "请完成二次验证", challenge);
        }
        String refreshToken = generateRefreshToken();
        TokenVO vo = transactionTemplate.execute(status -> {
            clearErrorCount(security);
            UmsUserLoginDevice session = deviceService.createSession(
                    user.getId(), dto.getDeviceInfo(), clientIp, refreshToken, REFRESH_TOKEN_TTL_DAYS,
                    loginType, channel);
            updateLastLogin(user, dto.getDeviceInfo(), clientIp);
            return issueTokens(user.getId(), loadRoleCodes(user.getId()), loadPermCodes(user.getId()),
                    session.getId(), session.getDeviceId(), refreshToken);
        });
        securityEventLogger.log("authn", dto.getAccount(), clientIp, "SUCCESS", "登录成功");
        return vo;
    }

    /**
     * MFA 二次验证（挑战凭证 + OTP；密码因子已在登录步骤 5 校验，tempToken 即证明）
     * <p>
     * GETDEL 原子单次消费在方法入口（事务外），防重放；OTP 错 = 挑战已消费，重试须重新登录。
     * </p>
     */
    public TokenVO verifyMfa(MfaVerifyDTO dto, String clientIp) {
        // 1. GETDEL 原子单次消费挑战凭证（不存在/已消费/过期 → null）
        ChallengeTokenService.ChallengeBinding binding = challengeTokenService.consume(dto.getTempToken());
        if (binding == null) {
            securityEventLogger.log("authn", "unknown", clientIp, "FAIL", "MFA 挑战过期或已消费");
            throw new BizException(ResultCode.MFA_CHALLENGE_EXPIRED);
        }
        // 2. 绑定 deviceId 与请求比对（防跨设备复用）
        if (!binding.deviceId().equals(dto.getDeviceInfo().getDeviceId())) {
            securityEventLogger.log("authn", String.valueOf(binding.userId()), clientIp, "FAIL", "MFA 挑战设备不符");
            throw new BizException(ResultCode.MFA_CHALLENGE_EXPIRED, "挑战凭证与当前设备不符");
        }
        // 3. 按 userId 查用户（防御分支，挑战签发时已验存在）
        UmsUser user = userService.getById(binding.userId());
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, INVALID_ACCOUNT_MESSAGE);
        }
        UmsUserAccountSecurity security = loadOrCreateSecurity(user.getId());
        checkLocked(security, user.getId(), clientIp);      // 4. lockTime 防御复检（签发后 5min 内可被锁）
        checkUserStatus(user, clientIp);      // 5. 状态复检（签发后 5min 内可变）
        if (!totpUtil.verify(security.getMfaSecret(), dto.getCode())) {   // 6. OTP 因子
            // 与密码共用防爆破：错 5 次锁 30min；挑战已消费，重试须重新登录
            increaseErrorCount(security, clientIp);
            securityEventLogger.log("authn", user.getUsername(), clientIp, "FAIL", "MFA 验证码错误");
            throw new BizException(ResultCode.UNAUTHORIZED, "验证码错误");
        }
        String refreshToken = generateRefreshToken();
        TokenVO vo = transactionTemplate.execute(status -> {
            clearErrorCount(security);
            // 登入方式/渠道取挑战绑定（登录入口已校验），防御 null 落 UNKNOWN
            UmsUserLoginDevice session = deviceService.createSession(
                    user.getId(), dto.getDeviceInfo(), clientIp, refreshToken, REFRESH_TOKEN_TTL_DAYS,
                    binding.loginType() == null ? LoginTypeEnum.UNKNOWN : binding.loginType(),
                    binding.channel() == null ? RegisterChannelEnum.UNKNOWN : binding.channel());
            updateLastLogin(user, dto.getDeviceInfo(), clientIp);
            return issueTokens(user.getId(), loadRoleCodes(user.getId()), loadPermCodes(user.getId()),
                    session.getId(), session.getDeviceId(), refreshToken);
        });
        securityEventLogger.log("authn", user.getUsername(), clientIp, "SUCCESS", "MFA 验证成功");
        return vo;
    }

    // ==================== 刷新 ====================

    /**
     * 刷新 token（轮换防重放）
     */
    public TokenVO refresh(RefreshDTO dto) {
        String oldRefreshTokenHash = com.sanye.strategy.common.util.HashUtil.sha256Hex(dto.getRefreshToken());
        UmsUserLoginDevice session = deviceService.findByRefreshTokenHash(oldRefreshTokenHash);
        if (session == null || !dto.getDeviceId().equals(session.getDeviceId())) {
            securityEventLogger.log("authn", session == null ? "unknown" : String.valueOf(session.getUserId()), null, "FAIL", "refresh 会话已失效");
            throw new BizException(ResultCode.TOKEN_EXPIRED, "会话已失效，请重新登录");
        }
        if (session.getExpireTime() != null && LocalDateTime.now().isAfter(session.getExpireTime())) {
            securityEventLogger.log("authn", String.valueOf(session.getUserId()), null, "FAIL", "refresh 会话已过期");
            throw new BizException(ResultCode.TOKEN_EXPIRED, "会话已过期，请重新登录");
        }
        if (jtiBlacklistService.isRevoked(session.getId())) {
            securityEventLogger.log("authn", String.valueOf(session.getUserId()), null, "FAIL", "refresh 会话已吊销");
            throw new BizException(ResultCode.TOKEN_EXPIRED, "会话已吊销，请重新登录");
        }
        UmsUser user = userService.getById(session.getUserId());
        if (user == null) {
            securityEventLogger.log("authn", "unknown", null, "FAIL", "refresh 会话已失效");
            throw new BizException(ResultCode.TOKEN_EXPIRED);
        }
        checkUserStatus(user, null);
        String newRefreshToken = generateRefreshToken();
        // 条件轮换：并发同令牌双刷时，后到者因哈希不匹配返回 false，视为会话已被轮换失效
        boolean rotated = deviceService.rotateRefreshToken(
                session.getId(), oldRefreshTokenHash, newRefreshToken, REFRESH_TOKEN_TTL_DAYS);
        if (!rotated) {
            securityEventLogger.log("authn", String.valueOf(session.getUserId()), null, "FAIL", "refresh 轮换竞态失效");
            throw new BizException(ResultCode.TOKEN_EXPIRED, "会话已失效，请重新登录");
        }
        // 新 accessToken 新 exp，清旧吊销记录
        jtiBlacklistService.remove(session.getId());
        String accessToken = jwtUtil.generateAccessToken(
                user.getId(), loadRoleCodes(user.getId()), loadPermCodes(user.getId()),
                session.getId(), session.getDeviceId());
        return buildTokenVO(accessToken, newRefreshToken);
    }

    // ==================== 登出 ====================

    /**
     * 登出：失效当前会话行 + jti 写黑名单（accessToken 即时失效）
     */
    public void logout() {
        UserContext context = UserContext.get();
        if (context == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        deviceService.invalidateSession(context.getJti());
        jtiBlacklistService.revoke(context.getJti(), jwtUtil.getAccessTokenTtlSeconds());
        securityEventLogger.log("authn", String.valueOf(context.getUserId()), null, "LOGOUT", "登出");
    }

    // ==================== 私有 ====================

    /**
     * 按登入方式解析账号归属（不再格式判型）
     * <p>
     * 账号查哪一列由 {@link LoginTypeEnum} 显式决定，替代早期「含 @→邮箱 / 纯数字→手机号 / 其余→用户名」
     * 的格式暴力判型——纯数字用户名会被误判为手机号导致查空（冒烟实测：username=12345678 登录查 null）。
     * 当前仅 {@code PASSWORD} 白名单放行：账号=用户名（uk_username 唯一，注册主键）。手机号/验证码
     * （phone + 国家码）与第三方（ums_user_auth 联查）分支随对应登录接口实现后启用；
     * 入口已由 {@link #validateLoginType} 把关，未开放分支不可达。
     * </p>
     */
    private UmsUser findByAccount(String account, LoginTypeEnum loginType) {
        return switch (loginType) {
            case PASSWORD -> userService.getOne(new DefaultQueryWrapper<UmsUser>().eq("username", account));
            case PHONE, SMS_CODE -> userService.getOne(new DefaultQueryWrapper<UmsUser>()
                    .eq("phone", account).eq("phone_country_code", DEFAULT_PHONE_COUNTRY_CODE));
            // 占位：第三方登录经 ums_user_auth 联查解出 userId，接口实现后替换；当前不可达
            case THIRD_PARTY, UNKNOWN -> null;
        };
    }

    private void checkUserStatus(UmsUser user, String clientIp) {
        if (user.getUserStatus() == UserStatusEnum.FROZEN) {
            securityEventLogger.log("account", user.getUsername(), clientIp, "FROZEN", "冻结账号尝试登录");
            throw new BizException(ResultCode.ACCOUNT_DISABLED, "账号已冻结");
        }
        if (user.getUserStatus() == UserStatusEnum.CANCELLED) {
            securityEventLogger.log("account", user.getUsername(), clientIp, "CANCELLED", "注销账号尝试登录");
            throw new BizException(ResultCode.ACCOUNT_DELETED, "账号已注销");
        }
    }

    private UmsUserAccountSecurity loadOrCreateSecurity(Long userId) {
        UmsUserAccountSecurity security = securityService.getOne(
                new DefaultQueryWrapper<UmsUserAccountSecurity>().eq("user_id", userId));
        if (security == null) {
            security = new UmsUserAccountSecurity();
            security.setUserId(userId);
            security.setPasswordErrorCount(0);
            security.setLockTime(null);
            security.setMfaStatus(YesNoEnum.NO);
        }
        return security;
    }

    private void checkLocked(UmsUserAccountSecurity security, Long userId, String clientIp) {
        if (security.getLockTime() != null && LocalDateTime.now().isBefore(security.getLockTime())) {
            securityEventLogger.log("account", String.valueOf(userId), clientIp, "LOCKED", "锁定账号尝试登录");
            throw new BizException(ResultCode.ACCOUNT_LOCKED, "账号已锁定，请稍后再试");
        }
    }

    private void increaseErrorCount(UmsUserAccountSecurity security, String clientIp) {
        // 原子化：共享事务内 FOR UPDATE 重读安全行（uk_user_id 唯一键锁单行），
        // 读-加-写收口一处，杜绝并发错密码计数丢失；行不存在（新用户）走插入路径
        transactionTemplate.execute(status -> {
            boolean locked = false;
            UmsUserAccountSecurity current = securityService.getOne(
                    new DefaultQueryWrapper<UmsUserAccountSecurity>()
                            .eq("user_id", security.getUserId())
                            .last("FOR UPDATE"));
            if (current == null) {
                int count = (security.getPasswordErrorCount() == null ? 0 : security.getPasswordErrorCount()) + 1;
                security.setPasswordErrorCount(count);
                if (count >= PASSWORD_ERROR_THRESHOLD) {
                    security.setLockTime(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
                    locked = true;
                }
                securityService.insert(security);
            } else {
                int count = (current.getPasswordErrorCount() == null ? 0 : current.getPasswordErrorCount()) + 1;
                current.setPasswordErrorCount(count);
                if (count >= PASSWORD_ERROR_THRESHOLD) {
                    current.setLockTime(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
                    locked = true;
                }
                securityService.updateById(current);
            }
            // 仅实际触发锁定时记录安全事件（防每次错密误报锁定）；account 类事件保留完整 IP（规格 6.3）
            if (locked) {
                securityEventLogger.log("account", String.valueOf(security.getUserId()), clientIp, "LOCKED", "密码/OTP 错误达阈值锁定 30min");
            }
            return null;
        });
    }

    private void clearErrorCount(UmsUserAccountSecurity security) {
        security.setPasswordErrorCount(0);
        security.setLockTime(null);
        if (security.getId() != null) {
            securityService.updateById(security);
        }
    }

    private void updateLastLogin(UmsUser user, DeviceInfo info, String clientIp) {
        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(clientIp);
        user.setLastLoginDeviceId(info == null ? null : info.getDeviceId());
        userService.updateById(user);
    }

    private TokenVO issueTokens(Long userId, List<String> roleCodes, List<String> permCodes,
                                Long sessionId, String deviceId, String refreshToken) {
        String accessToken = jwtUtil.generateAccessToken(userId, roleCodes, permCodes, sessionId, deviceId);
        return buildTokenVO(accessToken, refreshToken);
    }

    /**
     * 注册默认绑定角色（NORMAL_USER，rbac.sql seed 保证存在；缺失即配置错误，fail-fast）
     */
    private void assignDefaultRole(Long userId) {
        UmsRole normalRole = roleService.getOne(new DefaultQueryWrapper<UmsRole>()
                .eq("role_code", DEFAULT_ROLE_CODE)
                .eq("status", RoleStatusEnum.NORMAL.getCode()));
        if (normalRole == null) {
            throw new BizException(ResultCode.INTERNAL_ERROR, "默认角色未初始化，请检查 rbac.sql 内置角色 seed");
        }
        userRoleService.assignRole(userId, normalRole.getId(), null);
    }

    /**
     * 加载用户生效角色码（联表查询，供签发 accessToken roles claim 快照）
     */
    private List<String> loadRoleCodes(Long userId) {
        return userRoleService.getRoleCodesByUserId(userId);
    }

    /**
     * 加载用户生效权限码并集（角色→权限码；SUPER_ADMIN 返回 {@code "*"} 通配，
     * 与 {@link RbacAuthzService#effectivePermissions} 实时语义一致——快照/实时两模型生效权限集对齐）
     * <p>
     * 供签发 accessToken perms claim 快照。非 SUPER_ADMIN 用户按角色联 {@code ums_role_permission}
     * 求权限码并集（LinkedHashSet 保序去重）。超 {@code jwt.perm-claim-max} 上限时由
     * {@link JwtUtil#generateAccessToken} safe-degrade 置空（宁拒勿越权）。
     * </p>
     */
    private List<String> loadPermCodes(Long userId) {
        List<String> roleCodes = loadRoleCodes(userId);
        if (roleCodes.contains("SUPER_ADMIN")) { return List.of(RbacAuthzService.WILDCARD); }
        Set<String> codes = new LinkedHashSet<>();
        for (String roleCode : roleCodes) {
            UmsRole role = roleService.getOne(new DefaultQueryWrapper<UmsRole>().eq("role_code", roleCode));
            if (role != null) {
                codes.addAll(rolePermissionService.getPermissionCodesByRoleId(role.getId()));
            }
        }
        return new ArrayList<>(codes);
    }

    private TokenVO buildTokenVO(String accessToken, String refreshToken) {
        TokenVO vo = new TokenVO();
        vo.setAccessToken(accessToken);
        vo.setRefreshToken(refreshToken);
        vo.setAccessExpiresIn((int) jwtUtil.getAccessTokenTtlSeconds());
        return vo;
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 校验前端传递的注册/登录渠道
     * <p>
     * 渠道由前端显式传（不再从 deviceType 推导），后端白名单校验；当前仅开放 H5/PC。
     * null/未知码/未开放渠道一律 400。白名单为业务策略，扩展时只改 {@link #OPEN_CHANNEL_CODES}。
     * </p>
     */
    private RegisterChannelEnum validateChannel(Integer channelCode) {
        RegisterChannelEnum channel = RegisterChannelEnum.valueOf(channelCode);
        if (channel == null || !OPEN_CHANNEL_CODES.contains(channel.getCode())) {
            throw new BizException(ResultCode.BAD_REQUEST, "当前仅支持 H5/PC 渠道，请更新客户端");
        }
        return channel;
    }

    /**
     * 校验前端传递的登入方式
     * <p>
     * 登入方式由前端显式传，后端白名单校验；当前仅开放账号密码（手机号/验证码/第三方接口未实现）。
     * null/未知码/未开放方式一律 400。白名单为业务策略，扩展时只改 {@link #OPEN_LOGIN_TYPE_CODES}。
     * </p>
     */
    private LoginTypeEnum validateLoginType(Integer loginTypeCode) {
        LoginTypeEnum loginType = LoginTypeEnum.valueOf(loginTypeCode);
        if (loginType == null || !OPEN_LOGIN_TYPE_CODES.contains(loginType.getCode())) {
            throw new BizException(ResultCode.BAD_REQUEST, "该登入方式暂未开放");
        }
        return loginType;
    }

    private void validatePasswordPolicy(String password) {
        if (password == null || password.length() < 8
                || !password.matches(".*[a-zA-Z].*") || !password.matches(".*[0-9].*")) {
            throw new BizException(ResultCode.BAD_REQUEST, "密码至少8位，且包含字母与数字");
        }
    }

    private String normalizeNullable(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
