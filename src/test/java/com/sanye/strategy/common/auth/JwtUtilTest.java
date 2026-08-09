package com.sanye.strategy.common.auth;

import com.sanye.strategy.enums.UserTypeEnum;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p>
 * {@link JwtUtil} 签发/解析验证
 * </p>
 *
 * @author 31372
 */
class JwtUtilTest {

    private static final String SECRET = "test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 30);

    @Test
    void shouldGenerateAndParseAccessToken() {
        String token = jwtUtil.generateAccessToken(1L, UserTypeEnum.NORMAL_USER, 10L, "dev-1");

        Claims claims = jwtUtil.parseToken(token);
        assertThat(claims.get("type", String.class)).isEqualTo("ACCESS");
        assertThat(claims.get("userId", Number.class).longValue()).isEqualTo(1L);
        assertThat(claims.get("userType", Number.class).intValue()).isEqualTo(UserTypeEnum.NORMAL_USER.getCode());
        // jti（JWT ID）按 RFC 7519 为字符串，jjwt 0.12.6 强约束 String；消费方以 Long.valueOf 还原会话行 ID
        assertThat(Long.valueOf(claims.get("jti", String.class))).isEqualTo(10L);
        assertThat(claims.get("deviceId", String.class)).isEqualTo("dev-1");
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(jwtUtil.getAccessTokenTtlSeconds()).isEqualTo(1800L);
    }

    @Test
    void shouldRejectTamperedToken() {
        String token = jwtUtil.generateAccessToken(1L, UserTypeEnum.NORMAL_USER, 10L, "dev-1");

        // 翻转型篡改：翻转签名段末字符，验签必失败。
        // 注：不能用 token + "x" —— 本 token 签名 base64url 长度恰为 4 的倍数，追加字符解码出的前导字节不变，
        //     jjwt 丢弃多余尾位后验签仍通过（已实测不抛异常），故改为逐位篡改。
        String tampered = token.substring(0, token.length() - 1)
                + (token.charAt(token.length() - 1) == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> jwtUtil.parseToken(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectExpiredToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .claims(Map.of("type", "ACCESS"))
                .subject("1")
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000L))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000L))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtUtil.parseToken(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void shouldRejectWrongSecret() {
        JwtUtil other = new JwtUtil("another-test-secret-0123456789-abcdefghijklmnopqrstuvwxyz", 30);
        String token = jwtUtil.generateAccessToken(1L, UserTypeEnum.NORMAL_USER, 10L, "dev-1");

        assertThatThrownBy(() -> other.parseToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldEmitAlgHs256() {
        String token = jwtUtil.generateAccessToken(1L, UserTypeEnum.NORMAL_USER, 10L, "dev-1");

        // 取 JWT 头段（第 0 段），base64url 解码后断言 alg 显式钉死 HS256
        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                StandardCharsets.UTF_8);
        assertThat(header).contains("\"alg\":\"HS256\"");
    }

    @Test
    void shouldRejectHs384SignedToken() {
        // 同密钥以 HS384 签发（49 字节密钥满足 HS384 ≥48 字节要求），验签侧须按钉死算法拒绝
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String hs384Token = Jwts.builder()
                .claims(Map.of("type", "ACCESS"))
                .subject("1")
                .signWith(key, Jwts.SIG.HS384)
                .compact();

        assertThatThrownBy(() -> jwtUtil.parseToken(hs384Token))
                .isInstanceOf(JwtException.class);
    }
}
