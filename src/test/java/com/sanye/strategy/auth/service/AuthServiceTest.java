package com.sanye.strategy.auth.service;

import com.sanye.strategy.auth.dto.LoginDTO;
import com.sanye.strategy.auth.dto.MfaChallengeVO;
import com.sanye.strategy.auth.dto.MfaVerifyDTO;
import com.sanye.strategy.auth.dto.RefreshDTO;
import com.sanye.strategy.auth.dto.RegisterDTO;
import com.sanye.strategy.auth.dto.TokenVO;
import com.sanye.strategy.common.auth.ChallengeTokenService;
import com.sanye.strategy.common.auth.JtiBlacklistService;
import com.sanye.strategy.common.auth.JwtUtil;
import com.sanye.strategy.common.auth.PasswordEncoder;
import com.sanye.strategy.common.auth.TotpUtil;
import com.sanye.strategy.common.auth.UserContext;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.ResultCode;
import com.sanye.strategy.device.dto.DeviceInfo;
import com.sanye.strategy.device.service.DeviceService;
import com.sanye.strategy.domain.UmsUser;
import com.sanye.strategy.domain.UmsUserAccountSecurity;
import com.sanye.strategy.domain.UmsUserLoginDevice;
import com.sanye.strategy.enums.UserStatusEnum;
import com.sanye.strategy.enums.UserTypeEnum;
import com.sanye.strategy.enums.YesNoEnum;
import com.sanye.strategy.service.UmsUserAccountSecurityService;
import com.sanye.strategy.service.UmsUserProfileService;
import com.sanye.strategy.service.UmsUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>
 * {@link AuthService} 认证流程分支验证（TransactionTemplate 以 mock 直通回调，无真实事务）
 * </p>
 *
 * @author 31372
 */
class AuthServiceTest {

    private UmsUserService userService;
    private UmsUserAccountSecurityService securityService;
    private UmsUserProfileService profileService;
    private DeviceService deviceService;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private TotpUtil totpUtil;
    private JtiBlacklistService jtiBlacklistService;
    private ChallengeTokenService challengeTokenService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userService = mock(UmsUserService.class);
        securityService = mock(UmsUserAccountSecurityService.class);
        profileService = mock(UmsUserProfileService.class);
        deviceService = mock(DeviceService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtUtil = mock(JwtUtil.class);
        totpUtil = mock(TotpUtil.class);
        jtiBlacklistService = mock(JtiBlacklistService.class);
        challengeTokenService = mock(ChallengeTokenService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(jwtUtil.getAccessTokenTtlSeconds()).thenReturn(1800L);
        when(jwtUtil.generateAccessToken(any(), any(), any(), any())).thenReturn("mock-access-token");
        authService = new AuthService(userService, securityService, profileService, deviceService,
                passwordEncoder, jwtUtil, totpUtil, jtiBlacklistService, challengeTokenService, transactionTemplate);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private RegisterDTO validRegisterDto() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("user01");
        dto.setPassword("abc12345");
        dto.setDeviceInfo(deviceInfo());
        return dto;
    }

    private LoginDTO loginDto(String account, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setAccount(account);
        dto.setPassword(password);
        dto.setDeviceInfo(deviceInfo());
        return dto;
    }

    private DeviceInfo deviceInfo() {
        DeviceInfo info = new DeviceInfo();
        info.setDeviceType(1);
        info.setDeviceId("device-1");
        return info;
    }

    private UmsUser normalUser() {
        UmsUser user = new UmsUser();
        user.setId(1L);
        user.setUsername("user01");
        user.setPassword("hashed");
        user.setUserType(UserTypeEnum.NORMAL_USER);
        user.setUserStatus(UserStatusEnum.NORMAL);
        return user;
    }

    private UmsUserAccountSecurity noMfaSecurity() {
        UmsUserAccountSecurity security = new UmsUserAccountSecurity();
        security.setId(100L);
        security.setUserId(1L);
        security.setPasswordErrorCount(0);
        security.setLockTime(null);
        security.setMfaStatus(YesNoEnum.NO);
        return security;
    }

    // ==================== 注册 ====================

    @Test
    void shouldRegisterAndIssueTokens() {
        when(userService.count(any())).thenReturn(0L);
        when(passwordEncoder.encode("abc12345")).thenReturn("$2a$10$encoded");
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        session.setId(10L);
        session.setDeviceId("device-1");
        when(deviceService.createSession(any(), any(), any(), any(), anyInt())).thenReturn(session);

        TokenVO vo = authService.register(validRegisterDto(), "127.0.0.1");

        assertThat(vo.getAccessToken()).isEqualTo("mock-access-token");
        assertThat(vo.getAccessExpiresIn()).isEqualTo(1800);
        verify(userService).insert(any());
        verify(securityService).insert(any());
        verify(profileService).insert(any());
        verify(deviceService).createSession(any(), any(), any(), any(), anyInt());
    }

    @Test
    void shouldRejectDuplicateUsername() {
        when(userService.count(any())).thenReturn(1L);

        assertThatThrownBy(() -> authService.register(validRegisterDto(), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    void shouldRejectWeakPassword() {
        RegisterDTO dto = validRegisterDto();
        dto.setPassword("12345678");

        assertThatThrownBy(() -> authService.register(dto, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    // ==================== 登录 ====================

    @Test
    void shouldLoginSuccessAndClearErrorCount() {
        when(userService.getOne(any())).thenReturn(normalUser());
        when(securityService.getOne(any())).thenReturn(noMfaSecurity());
        when(passwordEncoder.matches("abc12345", "hashed")).thenReturn(true);
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        session.setId(10L);
        session.setDeviceId("device-1");
        when(deviceService.createSession(any(), any(), any(), any(), anyInt())).thenReturn(session);

        TokenVO vo = authService.login(loginDto("user01", "abc12345"), "127.0.0.1");

        assertThat(vo.getAccessToken()).isEqualTo("mock-access-token");
        verify(securityService).updateById(any());
        verify(userService).updateById(any());
    }

    @Test
    void shouldNotDistinguishWrongPasswordOrMissingUser() {
        when(userService.getOne(any())).thenReturn(null);

        assertThatThrownBy(() -> authService.login(loginDto("nobody", "abc12345"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void shouldIncreaseErrorCountOnWrongPassword() {
        when(userService.getOne(any())).thenReturn(normalUser());
        when(securityService.getOne(any())).thenReturn(noMfaSecurity());
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginDto("user01", "wrong"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);

        verify(securityService).updateById(any());
        verify(deviceService, never()).createSession(any(), any(), any(), any(), anyInt());
    }

    @Test
    void shouldLockAccountAtThreshold() {
        UmsUserAccountSecurity security = noMfaSecurity();
        security.setPasswordErrorCount(4);
        when(userService.getOne(any())).thenReturn(normalUser());
        when(securityService.getOne(any())).thenReturn(security);
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginDto("user01", "wrong"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);

        org.mockito.ArgumentCaptor<UmsUserAccountSecurity> captor =
                org.mockito.ArgumentCaptor.forClass(UmsUserAccountSecurity.class);
        verify(securityService).updateById(captor.capture());
        assertThat(captor.getValue().getPasswordErrorCount()).isEqualTo(5);
        assertThat(captor.getValue().getLockTime()).isNotNull();
    }

    @Test
    void shouldRejectLockedAccount() {
        UmsUserAccountSecurity security = noMfaSecurity();
        security.setLockTime(LocalDateTime.now().plusMinutes(10));
        when(userService.getOne(any())).thenReturn(normalUser());
        when(securityService.getOne(any())).thenReturn(security);

        assertThatThrownBy(() -> authService.login(loginDto("user01", "abc12345"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.ACCOUNT_LOCKED);
    }

    @Test
    void shouldRequireMfaAndIssueChallengeToken() {
        UmsUserAccountSecurity security = noMfaSecurity();
        security.setMfaStatus(YesNoEnum.YES);
        when(userService.getOne(any())).thenReturn(normalUser());
        when(securityService.getOne(any())).thenReturn(security);
        when(passwordEncoder.matches("abc12345", "hashed")).thenReturn(true);
        when(challengeTokenService.issue(1L, "device-1", 300)).thenReturn("challenge-token");

        Throwable thrown = catchThrowable(() -> authService.login(loginDto("user01", "abc12345"), "127.0.0.1"));

        assertThat(thrown).isInstanceOf(BizException.class);
        BizException ex = (BizException) thrown;
        assertThat(ex.getResultCode()).isEqualTo(ResultCode.MFA_REQUIRED);
        assertThat(ex.getPayload()).isInstanceOf(MfaChallengeVO.class);
        MfaChallengeVO challenge = (MfaChallengeVO) ex.getPayload();
        assertThat(challenge.getTempToken()).isEqualTo("challenge-token");
        assertThat(challenge.getExpiresIn()).isEqualTo(300);
        verify(challengeTokenService).issue(1L, "device-1", 300);
        verify(deviceService, never()).createSession(any(), any(), any(), any(), anyInt());
        verify(securityService, never()).updateById(any());   // MFA 分支不清计数
    }

    @Test
    void shouldRejectFrozenAccount() {
        UmsUser user = normalUser();
        user.setUserStatus(UserStatusEnum.FROZEN);
        when(userService.getOne(any())).thenReturn(user);

        assertThatThrownBy(() -> authService.login(loginDto("user01", "abc12345"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.ACCOUNT_DISABLED);
    }

    @Test
    void shouldRejectCancelledAccount() {
        UmsUser user = normalUser();
        user.setUserStatus(UserStatusEnum.CANCELLED);
        when(userService.getOne(any())).thenReturn(user);

        assertThatThrownBy(() -> authService.login(loginDto("user01", "abc12345"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.ACCOUNT_DELETED);
    }

    // ==================== MFA 验证 ====================

    @Test
    void shouldVerifyMfaAndIssueTokens() {
        when(challengeTokenService.consume("challenge-token"))
                .thenReturn(new ChallengeTokenService.ChallengeBinding(1L, "device-1"));
        when(userService.getById(1L)).thenReturn(normalUser());
        UmsUserAccountSecurity security = noMfaSecurity();
        security.setMfaSecret("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        when(securityService.getOne(any())).thenReturn(security);
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        session.setId(10L);
        session.setDeviceId("device-1");
        when(deviceService.createSession(any(), any(), any(), any(), anyInt())).thenReturn(session);

        MfaVerifyDTO dto = new MfaVerifyDTO();
        dto.setTempToken("challenge-token");
        dto.setCode("123456");
        dto.setDeviceInfo(deviceInfo());

        // TotpUtil 现为实例 Bean（Task 13 双重 Bean 化），经实例 mock 桩定
        when(totpUtil.verify("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "123456")).thenReturn(true);
        TokenVO vo = authService.verifyMfa(dto, "127.0.0.1");

        assertThat(vo.getAccessToken()).isEqualTo("mock-access-token");
        verify(challengeTokenService).consume("challenge-token");
        verify(securityService).updateById(any());
    }

    @Test
    void shouldRejectWrongMfaCodeAndIncrementCounter() {
        when(challengeTokenService.consume("challenge-token"))
                .thenReturn(new ChallengeTokenService.ChallengeBinding(1L, "device-1"));
        when(userService.getById(1L)).thenReturn(normalUser());
        UmsUserAccountSecurity security = noMfaSecurity();
        security.setMfaSecret("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        when(securityService.getOne(any())).thenReturn(security);

        MfaVerifyDTO dto = new MfaVerifyDTO();
        dto.setTempToken("challenge-token");
        dto.setCode("000000");
        dto.setDeviceInfo(deviceInfo());

        // TotpUtil 现为实例 Bean（Task 13 双重 Bean 化），经实例 mock 桩定
        when(totpUtil.verify(any(), any())).thenReturn(false);
        Throwable thrown = catchThrowable(() -> authService.verifyMfa(dto, "127.0.0.1"));

        assertThat(thrown).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
        verify(challengeTokenService).consume("challenge-token");
        verify(securityService).updateById(any());
    }

    @Test
    void shouldRejectConsumedOrExpiredChallenge() {
        when(challengeTokenService.consume("gone")).thenReturn(null);
        MfaVerifyDTO dto = new MfaVerifyDTO();
        dto.setTempToken("gone");
        dto.setCode("123456");
        dto.setDeviceInfo(deviceInfo());

        assertThatThrownBy(() -> authService.verifyMfa(dto, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.MFA_CHALLENGE_EXPIRED);
        verify(securityService, never()).updateById(any());
    }

    @Test
    void shouldRejectChallengeFromAnotherDevice() {
        when(challengeTokenService.consume("challenge-token"))
                .thenReturn(new ChallengeTokenService.ChallengeBinding(1L, "device-1"));
        DeviceInfo other = deviceInfo();
        other.setDeviceId("other-device");
        MfaVerifyDTO dto = new MfaVerifyDTO();
        dto.setTempToken("challenge-token");
        dto.setCode("123456");
        dto.setDeviceInfo(other);

        assertThatThrownBy(() -> authService.verifyMfa(dto, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.MFA_CHALLENGE_EXPIRED);
    }

    // ==================== 刷新 ====================

    @Test
    void shouldRotateRefreshTokenAndIssueNewAccess() {
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        session.setId(10L);
        session.setUserId(1L);
        session.setDeviceId("device-1");
        session.setExpireTime(LocalDateTime.now().plusDays(14));
        when(deviceService.findByRefreshTokenHash(any())).thenReturn(session);
        when(jtiBlacklistService.isRevoked(10L)).thenReturn(false);
        when(userService.getById(1L)).thenReturn(normalUser());
        when(deviceService.rotateRefreshToken(any(), any(), any(), anyInt())).thenReturn(true);

        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken("refresh-token");
        dto.setDeviceId("device-1");

        TokenVO vo = authService.refresh(dto);

        assertThat(vo.getAccessToken()).isEqualTo("mock-access-token");
        assertThat(vo.getRefreshToken()).isNotEqualTo("refresh-token");
        verify(deviceService).rotateRefreshToken(any(), any(), any(), anyInt());
        verify(jtiBlacklistService).remove(10L);
    }

    @Test
    void shouldRejectRefreshWithRevokedSession() {
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        session.setId(10L);
        session.setUserId(1L);
        session.setDeviceId("device-1");
        session.setExpireTime(LocalDateTime.now().plusDays(14));
        when(deviceService.findByRefreshTokenHash(any())).thenReturn(session);
        when(jtiBlacklistService.isRevoked(10L)).thenReturn(true);

        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken("refresh-token");
        dto.setDeviceId("device-1");

        assertThatThrownBy(() -> authService.refresh(dto))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.TOKEN_EXPIRED);
        verify(deviceService, never()).rotateRefreshToken(any(), any(), any(), anyInt());
    }

    @Test
    void shouldRejectRefreshWithDeviceMismatch() {
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        session.setId(10L);
        session.setUserId(1L);
        session.setDeviceId("other-device");
        session.setExpireTime(LocalDateTime.now().plusDays(14));
        when(deviceService.findByRefreshTokenHash(any())).thenReturn(session);

        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken("refresh-token");
        dto.setDeviceId("device-1");

        assertThatThrownBy(() -> authService.refresh(dto))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.TOKEN_EXPIRED);
    }

    @Test
    void shouldRejectRefreshWhenRotationFails() {
        // 并发同令牌双刷：轮换返回 false（库中哈希已非旧值）→ 401 TOKEN_EXPIRED
        UmsUserLoginDevice session = new UmsUserLoginDevice();
        session.setId(10L);
        session.setUserId(1L);
        session.setDeviceId("device-1");
        session.setExpireTime(LocalDateTime.now().plusDays(14));
        when(deviceService.findByRefreshTokenHash(any())).thenReturn(session);
        when(jtiBlacklistService.isRevoked(10L)).thenReturn(false);
        when(userService.getById(1L)).thenReturn(normalUser());
        when(deviceService.rotateRefreshToken(any(), any(), any(), anyInt())).thenReturn(false);

        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken("refresh-token");
        dto.setDeviceId("device-1");

        assertThatThrownBy(() -> authService.refresh(dto))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.TOKEN_EXPIRED);
        verify(jtiBlacklistService, never()).remove(10L);
    }

    // ==================== 登出 ====================

    @Test
    void shouldLogoutInvalidateSessionAndBlacklist() {
        UserContext.set(new UserContext(1L, UserTypeEnum.NORMAL_USER, 10L, "device-1"));

        authService.logout();

        verify(deviceService).invalidateSession(10L);
        verify(jtiBlacklistService).revoke(10L, 1800L);
    }
}
