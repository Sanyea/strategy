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
 * {@link JtiBlacklistService} Redis 命令转发验证
 * </p>
 *
 * @author 31372
 */
class JtiBlacklistServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final JtiBlacklistService jtiBlacklistService = new JtiBlacklistService(redisTemplate);

    @Test
    void shouldRevokeWithSetExAndTtl() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);

        jtiBlacklistService.revoke(123L, 1800L);

        verify(ops).set("jti:123", "1", Duration.ofSeconds(1800));
    }

    @Test
    void shouldCheckRevokedState() {
        when(redisTemplate.hasKey("jti:123")).thenReturn(true);
        assertThat(jtiBlacklistService.isRevoked(123L)).isTrue();

        when(redisTemplate.hasKey("jti:456")).thenReturn(false);
        assertThat(jtiBlacklistService.isRevoked(456L)).isFalse();
    }

    @Test
    void shouldRemoveFromBlacklist() {
        jtiBlacklistService.remove(123L);
        verify(redisTemplate).delete("jti:123");
    }
}
