package com.sanye.strategy.common.interceptor;

import com.sanye.strategy.common.auth.JtiBlacklistService;
import com.sanye.strategy.common.auth.JwtUtil;
import com.sanye.strategy.common.auth.UserContext;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.response.ResultCode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <p>
 * {@link TokenAuthInterceptor} 认证链验证（白名单由 WebMvcConfig 注册排除，拦截器自身不感知）
 * </p>
 *
 * @author 31372
 */
class TokenAuthInterceptorTest {

    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final JtiBlacklistService jtiBlacklistService = mock(JtiBlacklistService.class);
    private final TokenAuthInterceptor interceptor = new TokenAuthInterceptor(jwtUtil, jtiBlacklistService);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldRejectMissingHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void shouldRejectWrongScheme() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void shouldFillUserContextOnValidAccessToken() {
        // jti 按 RFC 7519 为 String（jjwt 0.12.6 强约束），mock 镜像真实 token 契约
        when(jwtUtil.parseToken("token")).thenReturn(Jwts.claims(Map.of(
                "type", "ACCESS", "userId", 1L, "userType", 1, "jti", "10", "deviceId", "dev-1")));
        when(jtiBlacklistService.isRevoked(10L)).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(UserContext.get().getUserId()).isEqualTo(1L);
        assertThat(UserContext.get().getJti()).isEqualTo(10L);
        assertThat(UserContext.get().getDeviceId()).isEqualTo("dev-1");
    }

    @Test
    void shouldRejectBlacklistedToken() {
        // jti 为 String，拦截器解析 "10" → 10L，与 isRevoked(10L) mock 匹配
        when(jwtUtil.parseToken("token")).thenReturn(Jwts.claims(Map.of(
                "type", "ACCESS", "userId", 1L, "userType", 1, "jti", "10", "deviceId", "dev-1")));
        when(jtiBlacklistService.isRevoked(10L)).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.TOKEN_EXPIRED);
    }

    @Test
    void shouldRejectNonAccessTypeToken() {
        when(jwtUtil.parseToken("token")).thenReturn(Jwts.claims(Map.of(
                "type", "REFRESH", "userId", 1L, "userType", 1, "jti", "10")));
        when(jtiBlacklistService.isRevoked(10L)).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void shouldRejectInvalidSignature() {
        when(jwtUtil.parseToken("bad")).thenThrow(new JwtException("验签失败"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void shouldRejectSignedButMalformedJti() {
        // 签名合法但 jti 非数值 → 还原会话行 ID 抛 NumberFormatException，收敛为 401 而非 500
        when(jwtUtil.parseToken("token")).thenReturn(Jwts.claims(Map.of(
                "type", "ACCESS", "userId", 1L, "userType", 1, "jti", "not-a-number", "deviceId", "dev-1")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void shouldRejectSignedButMissingUserId() {
        // 签名合法但缺 userId → claims.get 返回 null 触发 NPE，收敛为 401 而非 500
        when(jwtUtil.parseToken("token")).thenReturn(Jwts.claims(Map.of(
                "type", "ACCESS", "userType", 1, "jti", "10")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    @Test
    void shouldClearUserContextAfterCompletion() {
        UserContext.set(new UserContext(1L, null, 10L, "dev-1"));

        interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);

        assertThat(UserContext.get()).isNull();
    }
}
