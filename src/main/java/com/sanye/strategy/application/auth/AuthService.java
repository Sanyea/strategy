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
import com.sanye.strategy.common.base.DefaultQueryWrapper;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.application.device.dto.DeviceInfo;
import com.sanye.strategy.application.device.DeviceService;
import com.sanye.strategy.domain.user.entity.UmsUser;
import com.sanye.strategy.domain.user.entity.UmsUserAccountSecurity;
import com.sanye.strategy.domain.user.entity.UmsUserLoginDevice;
import com.sanye.strategy.domain.user.entity.UmsUserProfile;
import com.sanye.strategy.domain.enums.DeviceTypeEnum;
import com.sanye.strategy.domain.enums.RegisterChannelEnum;
import com.sanye.strategy.domain.enums.UserStatusEnum;
import com.sanye.strategy.domain.enums.UserTypeEnum;
import com.sanye.strategy.domain.enums.YesNoEnum;
import com.sanye.strategy.domain.user.repository.UmsUserAccountSecurityService;
import com.sanye.strategy.domain.user.repository.UmsUserProfileService;
import com.sanye.strategy.domain.user.repository.UmsUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

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
 *   AuthService → userService.getOne(判型)          // 手机/邮箱/用户名
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

    private final UmsUserService userService;
    private final UmsUserAccountSecurityService securityService;
    private final UmsUserProfileService profileService;
    private final DeviceService deviceService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TotpUtil totpUtil;
    private final JtiBlacklistService jtiBlacklistService;
    private final ChallengeTokenService challengeTokenService;
    private final TransactionTemplate transactionTemplate;

    // ==================== 注册 ====================

    /**
     * 注册并返回双 Token
     *
     * @param dto      注册参数
     * @param clientIp 客户端 IP
     */
    public TokenVO register(RegisterDTO dto, String clientIp) {
        validatePasswordPolicy(dto.getPassword());
        if (userService.count(new DefaultQueryWrapper<UmsUser>()
                .eq("username", dto.getUsername())) > 0) {
            throw new BizException(ResultCode.CONFLICT, "用户名已被占用");
        }
        String refreshToken = generateRefreshToken();
        UmsUser user = buildRegisterUser(dto, clientIp);
        return transactionTemplate.execute(status -> {
            userService.insert(user);
            initSecurity(user.getId());
            initProfile(user.getId());
            UmsUserLoginDevice session = deviceService.createSession(
                    user.getId(), dto.getDeviceInfo(), clientIp, refreshToken, REFRESH_TOKEN_TTL_DAYS);
            return issueTokens(user, session.getId(), session.getDeviceId(), refreshToken);
        });
    }

    private UmsUser buildRegisterUser(RegisterDTO dto, String clientIp) {
        UmsUser user = new UmsUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname() == null || dto.getNickname().isBlank()
                ? dto.getUsername() : dto.getNickname());
        user.setPhone(normalizeNullable(dto.getPhone()));
        user.setEmail(normalizeNullable(dto.getEmail()));
        user.setPhoneCountryCode(DEFAULT_PHONE_COUNTRY_CODE);
        user.setUserType(UserTypeEnum.NORMAL_USER);
        user.setUserStatus(UserStatusEnum.NORMAL);
        user.setRegisterChannel(mapChannel(dto.getDeviceInfo()));
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
        UmsUser user = findByAccount(dto.getAccount());
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, INVALID_ACCOUNT_MESSAGE);
        }
        checkUserStatus(user);
        UmsUserAccountSecurity security = loadOrCreateSecurity(user.getId());
        checkLocked(security);
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            increaseErrorCount(security);
            throw new BizException(ResultCode.UNAUTHORIZED, INVALID_ACCOUNT_MESSAGE);
        }
        if (YesNoEnum.YES.equals(security.getMfaStatus())) {
            // MFA 开启：签发 5min 挑战凭证（Redis-only，零 DB 写，不清计数——OTP 通过 verifyMfa 才清）
            String tempToken = challengeTokenService.issue(
                    user.getId(), dto.getDeviceInfo().getDeviceId(), CHALLENGE_TTL_SECONDS);
            MfaChallengeVO challenge = new MfaChallengeVO();
            challenge.setTempToken(tempToken);
            challenge.setExpiresIn(CHALLENGE_TTL_SECONDS);
            throw new BizException(ResultCode.MFA_REQUIRED, "请完成二次验证", challenge);
        }
        String refreshToken = generateRefreshToken();
        return transactionTemplate.execute(status -> {
            clearErrorCount(security);
            UmsUserLoginDevice session = deviceService.createSession(
                    user.getId(), dto.getDeviceInfo(), clientIp, refreshToken, REFRESH_TOKEN_TTL_DAYS);
            updateLastLogin(user, dto.getDeviceInfo(), clientIp);
            return issueTokens(user, session.getId(), session.getDeviceId(), refreshToken);
        });
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
            throw new BizException(ResultCode.MFA_CHALLENGE_EXPIRED);
        }
        // 2. 绑定 deviceId 与请求比对（防跨设备复用）
        if (!binding.deviceId().equals(dto.getDeviceInfo().getDeviceId())) {
            throw new BizException(ResultCode.MFA_CHALLENGE_EXPIRED, "挑战凭证与当前设备不符");
        }
        // 3. 按 userId 查用户（防御分支，挑战签发时已验存在）
        UmsUser user = userService.getById(binding.userId());
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, INVALID_ACCOUNT_MESSAGE);
        }
        UmsUserAccountSecurity security = loadOrCreateSecurity(user.getId());
        checkLocked(security);      // 4. lockTime 防御复检（签发后 5min 内可被锁）
        checkUserStatus(user);      // 5. 状态复检（签发后 5min 内可变）
        if (!totpUtil.verify(security.getMfaSecret(), dto.getCode())) {   // 6. OTP 因子
            // 与密码共用防爆破：错 5 次锁 30min；挑战已消费，重试须重新登录
            increaseErrorCount(security);
            throw new BizException(ResultCode.UNAUTHORIZED, "验证码错误");
        }
        String refreshToken = generateRefreshToken();
        return transactionTemplate.execute(status -> {
            clearErrorCount(security);
            UmsUserLoginDevice session = deviceService.createSession(
                    user.getId(), dto.getDeviceInfo(), clientIp, refreshToken, REFRESH_TOKEN_TTL_DAYS);
            updateLastLogin(user, dto.getDeviceInfo(), clientIp);
            return issueTokens(user, session.getId(), session.getDeviceId(), refreshToken);
        });
    }

    // ==================== 刷新 ====================

    /**
     * 刷新 token（轮换防重放）
     */
    public TokenVO refresh(RefreshDTO dto) {
        String oldRefreshTokenHash = com.sanye.strategy.common.util.HashUtil.sha256Hex(dto.getRefreshToken());
        UmsUserLoginDevice session = deviceService.findByRefreshTokenHash(oldRefreshTokenHash);
        if (session == null || !dto.getDeviceId().equals(session.getDeviceId())) {
            throw new BizException(ResultCode.TOKEN_EXPIRED, "会话已失效，请重新登录");
        }
        if (session.getExpireTime() != null && LocalDateTime.now().isAfter(session.getExpireTime())) {
            throw new BizException(ResultCode.TOKEN_EXPIRED, "会话已过期，请重新登录");
        }
        if (jtiBlacklistService.isRevoked(session.getId())) {
            throw new BizException(ResultCode.TOKEN_EXPIRED, "会话已吊销，请重新登录");
        }
        UmsUser user = userService.getById(session.getUserId());
        if (user == null) {
            throw new BizException(ResultCode.TOKEN_EXPIRED);
        }
        checkUserStatus(user);
        String newRefreshToken = generateRefreshToken();
        // 条件轮换：并发同令牌双刷时，后到者因哈希不匹配返回 false，视为会话已被轮换失效
        boolean rotated = deviceService.rotateRefreshToken(
                session.getId(), oldRefreshTokenHash, newRefreshToken, REFRESH_TOKEN_TTL_DAYS);
        if (!rotated) {
            throw new BizException(ResultCode.TOKEN_EXPIRED, "会话已失效，请重新登录");
        }
        // 新 accessToken 新 exp，清旧吊销记录
        jtiBlacklistService.remove(session.getId());
        String accessToken = jwtUtil.generateAccessToken(
                user.getId(), user.getUserType(), session.getId(), session.getDeviceId());
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
    }

    // ==================== 私有 ====================

    private UmsUser findByAccount(String account) {
        DefaultQueryWrapper<UmsUser> wrapper = new DefaultQueryWrapper<>();
        if (account.contains("@")) {
            wrapper.eq("email", account);
        } else if (account.matches("^[0-9+]+$")) {
            wrapper.eq("phone", account).eq("phone_country_code", DEFAULT_PHONE_COUNTRY_CODE);
        } else {
            wrapper.eq("username", account);
        }
        return userService.getOne(wrapper);
    }

    private void checkUserStatus(UmsUser user) {
        if (user.getUserStatus() == UserStatusEnum.FROZEN) {
            throw new BizException(ResultCode.ACCOUNT_DISABLED, "账号已冻结");
        }
        if (user.getUserStatus() == UserStatusEnum.CANCELLED) {
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

    private void checkLocked(UmsUserAccountSecurity security) {
        if (security.getLockTime() != null && LocalDateTime.now().isBefore(security.getLockTime())) {
            throw new BizException(ResultCode.ACCOUNT_LOCKED, "账号已锁定，请稍后再试");
        }
    }

    private void increaseErrorCount(UmsUserAccountSecurity security) {
        // 原子化：共享事务内 FOR UPDATE 重读安全行（uk_user_id 唯一键锁单行），
        // 读-加-写收口一处，杜绝并发错密码计数丢失；行不存在（新用户）走插入路径
        transactionTemplate.execute(status -> {
            UmsUserAccountSecurity current = securityService.getOne(
                    new DefaultQueryWrapper<UmsUserAccountSecurity>()
                            .eq("user_id", security.getUserId())
                            .last("FOR UPDATE"));
            if (current == null) {
                int count = (security.getPasswordErrorCount() == null ? 0 : security.getPasswordErrorCount()) + 1;
                security.setPasswordErrorCount(count);
                if (count >= PASSWORD_ERROR_THRESHOLD) {
                    security.setLockTime(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
                }
                securityService.insert(security);
            } else {
                int count = (current.getPasswordErrorCount() == null ? 0 : current.getPasswordErrorCount()) + 1;
                current.setPasswordErrorCount(count);
                if (count >= PASSWORD_ERROR_THRESHOLD) {
                    current.setLockTime(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
                }
                securityService.updateById(current);
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

    private TokenVO issueTokens(UmsUser user, Long sessionId, String deviceId, String refreshToken) {
        String accessToken = jwtUtil.generateAccessToken(
                user.getId(), user.getUserType(), sessionId, deviceId);
        return buildTokenVO(accessToken, refreshToken);
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

    private RegisterChannelEnum mapChannel(DeviceInfo info) {
        if (info == null || info.getDeviceType() == null) {
            return RegisterChannelEnum.UNKNOWN;
        }
        DeviceTypeEnum type = DeviceTypeEnum.valueOf(info.getDeviceType());
        if (type == null) {
            return RegisterChannelEnum.UNKNOWN;
        }
        return switch (type) {
            case PC -> RegisterChannelEnum.PC;
            case MINI_PROGRAM -> RegisterChannelEnum.MINI_PROGRAM;
            default -> RegisterChannelEnum.APP; // PHONE/PAD
        };
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
