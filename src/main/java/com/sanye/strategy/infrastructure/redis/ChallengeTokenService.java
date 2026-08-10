package com.sanye.strategy.infrastructure.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * <p>
 * MFA 挑战凭证（tempToken）— Redis 短效一次性瞬态凭证
 * </p>
 * <p>
 * 登录 MFA 分支签发 32B 随机 tempToken，Redis {@code SETEX mfa:{tempToken} {userId}:{deviceId} ttl}（5min）；
 * verify 时 {@code GETDEL} 原子单次消费（{@link ValueOperations#getAndDelete(Object)}）——命中即删，防重放/双消费竞态。
 * 记录随 TTL 自动过期，无手工清理。键域 {@code mfa:*} 与 {@link JtiBlacklistService}（{@code jti:*}）并列，Redis 双用途。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：挑战凭证存储适配器，隔离 Redis 命令细节。</li>
 *   <li>优缺点：GETDEL 原子消费天然防重放、免事后作废步骤；缺点：依赖 Redis 可用性
 *       （不可用时签发/消费抛连接异常走 500，运维兜底，与 JtiBlacklistService 同策略）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Service
public class ChallengeTokenService {

    private static final String KEY_PREFIX = "mfa:";

    private final StringRedisTemplate redisTemplate;

    public ChallengeTokenService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 签发挑战凭证
     *
     * @param userId     账号 ID（绑定，verify 时解出）
     * @param deviceId   设备 ID（绑定，verify 比对防跨设备）
     * @param ttlSeconds 存活秒数（本批 300 = 5min）
     * @return 32B hex tempToken（64 字符）
     */
    public String issue(Long userId, String deviceId, int ttlSeconds) {
        String token = generateToken();
        redisTemplate.opsForValue().set(KEY_PREFIX + token, userId + ":" + deviceId, Duration.ofSeconds(ttlSeconds));
        return token;
    }

    /**
     * 原子单次消费挑战凭证（GETDEL）
     *
     * @param tempToken 挑战凭证
     * @return 绑定信息（userId/deviceId）；null 表示已消费/过期/不存在
     */
    public ChallengeBinding consume(String tempToken) {
        String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + tempToken);
        if (value == null) {
            return null;
        }
        int separator = value.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        try {
            return new ChallengeBinding(Long.parseLong(value.substring(0, separator)), value.substring(separator + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 挑战绑定信息（userId + deviceId） */
    public record ChallengeBinding(Long userId, String deviceId) {
    }
}
