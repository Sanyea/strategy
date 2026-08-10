package com.sanye.strategy.infrastructure.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * <p>
 * accessToken jti 吊销黑名单 — Redis 秒级冻结
 * </p>
 * <p>
 * 吊销操作（登出/踢设备/改密/冻结/注销）写黑名单 {@code SETEX jti:{jti} ttl 1}（TTL = 剩余 exp）；
 * 拦截器验签后 {@code EXISTS jti:{jti}} 命中即拒——accessToken 即时失效，不等 JWT 自身 TTL 过期。
 * 记录随 TTL 自动过期，无手工清理。Redis 双用途：jti 吊销黑名单 + MFA 挑战凭证（键域 {@code mfa:*}，见 {@link ChallengeTokenService}），不存会话/业务缓存（refresh 会话仍落库）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：吊销机制存储适配器，隔离 Redis 命令细节。</li>
 *   <li>优缺点：秒级生效、TTL 自清理、实现简单；缺点：依赖 Redis 可用性（不可用则拦截器检活失败，
 *       以 fail-open 或 fail-closed 取舍——本实现不可用时抛连接异常走 500，运维兜底）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Service
public class JtiBlacklistService {

    private static final String KEY_PREFIX = "jti:";
    private static final String VALUE = "1";

    private final StringRedisTemplate redisTemplate;

    public JtiBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 吊销会话（写黑名单，TTL = accessToken 剩余有效期上限）
     *
     * @param jti        会话行 ID
     * @param ttlSeconds 黑名单存活秒数（取 accessToken TTL，保守覆盖剩余 exp）
     */
    public void revoke(Long jti, long ttlSeconds) {
        redisTemplate.opsForValue().set(KEY_PREFIX + jti, VALUE, Duration.ofSeconds(ttlSeconds));
    }

    /**
     * 会话是否已被吊销
     */
    public boolean isRevoked(Long jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
    }

    /**
     * 清除黑名单记录（refresh 轮换后新 token 新 exp，清旧吊销记录）
     */
    public void remove(Long jti) {
        redisTemplate.delete(KEY_PREFIX + jti);
    }
}
