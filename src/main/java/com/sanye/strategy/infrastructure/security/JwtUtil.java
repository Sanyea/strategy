package com.sanye.strategy.infrastructure.security;

import com.sanye.strategy.domain.enums.UserTypeEnum;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.lang.NestedCollection;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecureDigestAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * <p>
 * JWT 工具 — HS256 签名签发/解析（签发与验签均显式钉死 {@link Jwts.SIG#HS256}，不随密钥长度推断）
 * </p>
 * <p>
 * accessToken claims：{@code type=ACCESS, userId, userType, jti, deviceId}（+ 标准 sub/iat/exp）。
 * {@code jti}（JWT ID）按 RFC 7519 为 case-sensitive 字符串：本类以 {@code String.valueOf(jti)} 落串，
 * 消费方（Task 11 拦截器）读取时用 {@code Long.valueOf(claims.get("jti", String.class))} 还原会话行 ID。
 * 密钥 ≥32 字节，从配置注入（{@code jwt.secret}），环境变量/密管覆盖，不进代码不入库。
 * 算法/密钥演进预留：签发时写入 {@code kid} 头（当前固定 "1"），轮换时新旧密钥并存验证。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：Token 生命周期工具，隔离 jjwt 细节。</li>
 *   <li>优缺点：无状态验签、免 DB 查询；缺点：吊销只能靠黑名单（本批 Redis jti 黑名单承接），
 *       且 HS256 密钥须妥善保管。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Component
public class JwtUtil {

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_USER_TYPE = "userType";
    private static final String CLAIM_JTI = "jti";
    private static final String CLAIM_DEVICE_ID = "deviceId";
    private static final String TYPE_ACCESS = "ACCESS";
    private static final String KID = "1";

    private final SecretKey secretKey;
    private final long accessTokenTtlMillis;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.access-token-ttl-minutes:30}") long accessTokenTtlMinutes) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlMillis = accessTokenTtlMinutes * 60_000L;
    }

    /**
     * 签发 accessToken
     *
     * @param userId   用户ID
     * @param userType 用户类型
     * @param jti      会话行 ID（吊销黑名单键）
     * @param deviceId 设备 ID
     * @return JWT 串
     */
    public String generateAccessToken(Long userId, UserTypeEnum userType, Long jti, String deviceId) {
        Date now = new Date();
        return Jwts.builder()
                .header().keyId(KID).and()
                .claims(Map.of(
                        CLAIM_TYPE, TYPE_ACCESS,
                        CLAIM_USER_ID, userId,
                        CLAIM_USER_TYPE, userType.getCode(),
                        CLAIM_JTI, String.valueOf(jti),
                        CLAIM_DEVICE_ID, deviceId))
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenTtlMillis))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 验签 + 过期校验并解析 claims
     * <p>仅接受 HS256 签名：构建验签解析器时逐个移除默认注册表中非 HS256 算法，
     * 注册表收敛为 {@link Jwts.SIG#HS256} 单元素，HS384/HS512/RS/ES/PS/EdDSA/none 签发的 token 一律拒绝。</p>
     * <p>注：jjwt 0.12.6 无 {@code only(...)} API，且 {@code sig().clear().add(...)} 不可用——{@code clear()}
     * 触发 {@code changed()} 以空集合重建注册表直接抛 {@code IllegalArgument}；
     * 逐个 {@code remove()} 每步以非空集合重建，可安全收敛到仅 HS256。</p>
     *
     * @param token JWT 串
     * @return claims
     * @throws io.jsonwebtoken.JwtException 验签失败 / 算法不符 / 过期
     */
    public Claims parseToken(String token) {
        JwtParserBuilder parserBuilder = Jwts.parser().verifyWith(secretKey);
        NestedCollection<SecureDigestAlgorithm<?, ?>, JwtParserBuilder> sig = parserBuilder.sig();
        for (SecureDigestAlgorithm<?, ?> algorithm : Jwts.SIG.get().values()) {
            if (!Jwts.SIG.HS256.getId().equals(algorithm.getId())) {
                sig.remove(algorithm);
            }
        }
        return parserBuilder.build().parseSignedClaims(token).getPayload();
    }

    /**
     * accessToken 有效期（秒），用于黑名单 TTL 上限
     */
    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlMillis / 1000L;
    }
}
