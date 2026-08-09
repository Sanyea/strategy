package com.sanye.strategy.common.auth;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>
 * {@link ChallengeTokenService} Redis 命令转发 + GETDEL 原子消费验证
 * </p>
 *
 * @author 31372
 */
class ChallengeTokenServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final ChallengeTokenService service = new ChallengeTokenService(redisTemplate);

    @Test
    void shouldIssueWithSetExAndRandomToken() {
        when(redisTemplate.opsForValue()).thenReturn(ops);

        String token = service.issue(1L, "device-1", 300);

        assertThat(token).matches("^[0-9a-f]{64}$");
        verify(ops).set("mfa:" + token, "1:device-1", Duration.ofSeconds(300));
    }

    @Test
    void shouldConsumeAtomicallyAndReturnBinding() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.getAndDelete("mfa:tok")).thenReturn("7:device-9");

        ChallengeTokenService.ChallengeBinding binding = service.consume("tok");

        assertThat(binding.userId()).isEqualTo(7L);
        assertThat(binding.deviceId()).isEqualTo("device-9");
        verify(ops).getAndDelete("mfa:tok");
    }

    @Test
    void shouldReturnNullWhenAlreadyConsumedOrExpired() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.getAndDelete("mfa:gone")).thenReturn(null);

        assertThat(service.consume("gone")).isNull();
    }
}
