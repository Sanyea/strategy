package com.sanye.strategy.common.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>
 * {@link PasswordEncoder} BCrypt 封装验证
 * </p>
 *
 * @author 31372
 */
class PasswordEncoderTest {

    private final PasswordEncoder passwordEncoder = new PasswordEncoder();

    @Test
    void shouldEncodeAndMatch() {
        String hash = passwordEncoder.encode("abc12345");

        assertThat(hash).startsWith("$2");
        assertThat(passwordEncoder.matches("abc12345", hash)).isTrue();
        assertThat(passwordEncoder.matches("wrong-pass", hash)).isFalse();
    }

    @Test
    void shouldReturnFalseOnNullOrEmptyEncoded() {
        assertThat(passwordEncoder.matches("abc12345", null)).isFalse();
        assertThat(passwordEncoder.matches("abc12345", "")).isFalse();
    }

    @Test
    void shouldReturnFalseOnMalformedStoredHash() {
        // 旧体系/损坏哈希：BCrypt 抛 IllegalArgumentException，降级视为不匹配（401 而非 500）
        assertThat(passwordEncoder.matches("abc12345", "not-a-bcrypt-hash")).isFalse();
    }

    @Test
    void shouldBeRandomSaltPerEncode() {
        assertThat(passwordEncoder.encode("abc12345"))
                .isNotEqualTo(passwordEncoder.encode("abc12345"));
    }
}
