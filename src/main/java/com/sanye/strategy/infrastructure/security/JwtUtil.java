package com.sanye.strategy.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.lang.NestedCollection;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecureDigestAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * JWT 工具 — HS256 签名签发/解析（签发与验签均显式钉死 {@link Jwts.SIG#HS256}，不随密钥长度推断）
 * </p>
 * <p>
 * accessToken claims：{@code type=ACCESS, userId, roles, perms, jti, deviceId}（+ 标准 sub/iat/exp）。
 * {@code roles} 为用户生效角色码数组（快照，签发时取自 ums_user_role 联表，拦截器零 DB 查询；
 * 角色变更经 refresh 轮换 / jti 黑名单生效，最长滞后 30min）。
 * {@code perms} 为用户生效权限码数组（快照，签发时经 角色→ums_role_permission 联表求并集；超
 * {@code jwt.perm-claim-max} 上限时 safe-degrade 置空并 ERROR 告警，宁拒勿越权）。
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
@Slf4j
@Component
public class JwtUtil {

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMS = "perms";
    private static final String CLAIM_JTI = "jti";
    private static final String CLAIM_DEVICE_ID = "deviceId";
    private static final String TYPE_ACCESS = "ACCESS";
    private static final String KID = "1";

    private final SecretKey secretKey;
    private final long accessTokenTtlMillis;
    private final long permClaimMax;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.access-token-ttl-minutes:30}") long accessTokenTtlMinutes,
                   @Value("${jwt.perm-claim-max:500}") long permClaimMax) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlMillis = accessTokenTtlMinutes * 60_000L;
        this.permClaimMax = permClaimMax;
    }

    /**
     * 签发 accessToken
     *
     * @param userId    用户ID
     * @param roleCodes 用户生效角色码（快照，签发时联表查询；null/空→空数组）
     * @param permCodes 用户生效权限码（快照，签发时联表查询；null/空→空数组，超 {@code permClaimMax}→safe-degrade 置空）
     * @param jti       会话行 ID（吊销黑名单键）
     * @param deviceId  设备 ID
     * @return JWT 串
     */
    public String generateAccessToken(Long userId, List<String> roleCodes, List<String> permCodes, Long jti, String deviceId) {
        // perms 超上限 → safe-degrade：置空 + ERROR 告警（宁拒勿越权）
        List<String> perms = permCodes == null ? List.of() : permCodes;
        if (perms.size() > permClaimMax) {
            log.error("用户 {} 权限码数量 {} 超上限 {}，perms claim 置空（safe-degrade）", userId, perms.size(), permClaimMax);
            perms = List.of();
        }
        Date now = new Date();
        return Jwts.builder()
                .header().keyId(KID).and()
                .claims(Map.of(
                        CLAIM_TYPE, TYPE_ACCESS,
                        CLAIM_USER_ID, userId,
                        CLAIM_ROLES, roleCodes == null ? List.of() : roleCodes,
                        CLAIM_PERMS, perms,
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
