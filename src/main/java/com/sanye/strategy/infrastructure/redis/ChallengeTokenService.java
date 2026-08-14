package com.sanye.strategy.infrastructure.redis;

import com.sanye.strategy.domain.enums.LoginTypeEnum;
import com.sanye.strategy.domain.enums.RegisterChannelEnum;
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
 * 登录 MFA 分支签发 32B 随机 tempToken，Redis {@code SETEX mfa:{tempToken} {userId}:{deviceId}:{loginType}:{channel} ttl}（5min）；
 * verify 时 {@code GETDEL} 原子单次消费（{@link ValueOperations#getAndDelete(Object)}）——命中即删，防重放/双消费竞态。
 * 记录随 TTL 自动过期，无手工清理。键域 {@code mfa:*} 与 {@link JtiBlacklistService}（{@code jti:*}）并列，Redis 双用途。
 * 登入方式与登录渠道随挑战绑定携带（登录入口已校验），verifyMfa 建会话复用，不重复传参、不二次校验。
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
     * @param loginType  登入方式（登录入口已校验，verify 建会话复用；可 null 落 0）
     * @param channel    登录渠道（登录入口已校验，verify 建会话复用；可 null 落 0）
     * @param ttlSeconds 存活秒数（本批 300 = 5min）
     * @return 32B hex tempToken（64 字符）
     */
    public String issue(Long userId, String deviceId, LoginTypeEnum loginType, RegisterChannelEnum channel, int ttlSeconds) {
        String token = generateToken();
        String value = userId + ":" + deviceId + ":" + code(loginType) + ":" + code(channel);
        redisTemplate.opsForValue().set(KEY_PREFIX + token, value, Duration.ofSeconds(ttlSeconds));
        return token;
    }

    /**
     * 原子单次消费挑战凭证（GETDEL）
     *
     * @param tempToken 挑战凭证
     * @return 绑定信息（userId/deviceId/登入方式/渠道）；null 表示已消费/过期/不存在/旧格式（部署前签发）
     */
    public ChallengeBinding consume(String tempToken) {
        String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + tempToken);
        if (value == null) {
            return null;
        }
        // 格式 userId:deviceId:loginType:channel；deviceId 可含 ':'，首段=userId、末两段=码、中间段重建 deviceId
        String[] parts = value.split(":");
        if (parts.length < 4) {
            return null;
        }
        try {
            Long userId = Long.parseLong(parts[0]);
            StringBuilder deviceId = new StringBuilder(parts[1]);
            for (int i = 2; i <= parts.length - 3; i++) {
                deviceId.append(':').append(parts[i]);
            }
            return new ChallengeBinding(userId, deviceId.toString(),
                    LoginTypeEnum.valueOf(safeInt(parts[parts.length - 2])),
                    RegisterChannelEnum.valueOf(safeInt(parts[parts.length - 1])));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 枚举码转落串码，null 落 0 */
    private static int code(LoginTypeEnum loginType) {
        return loginType == null ? 0 : loginType.getCode();
    }

    /** 枚举码转落串码，null 落 0 */
    private static int code(RegisterChannelEnum channel) {
        return channel == null ? 0 : channel.getCode();
    }

    /** 安全解析整型，非法返回 null（不抛，交由 valueOf 兜底） */
    private static Integer safeInt(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 挑战绑定信息（userId + deviceId + 登入方式 + 登录渠道） */
    public record ChallengeBinding(Long userId, String deviceId,
                                   LoginTypeEnum loginType, RegisterChannelEnum channel) {
    }
}
