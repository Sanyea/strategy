package com.sanye.strategy.common.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>
 * {@link TotpUtil} RFC 6238 测试向量验证
 * </p>
 *
 * @author 31372
 */
class TotpUtilTest {

    // RFC 6238 Appendix B：ASCII "12345678901234567890" 的 Base32 编码
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void shouldMatchRfc6238TestVectors() {
        assertThat(new TotpUtil().generateAt(RFC_SECRET, 59L)).isEqualTo("287082");
        assertThat(new TotpUtil().generateAt(RFC_SECRET, 1111111109L)).isEqualTo("081804");
        assertThat(new TotpUtil().generateAt(RFC_SECRET, 1234567890L)).isEqualTo("005924");
        assertThat(new TotpUtil().generateAt(RFC_SECRET, 2000000000L)).isEqualTo("279037");
    }

    @Test
    void shouldReturnFalseForNullSecretOrCode() {
        assertThat(new TotpUtil().verify(null, "123456")).isFalse();
        assertThat(new TotpUtil().verify(RFC_SECRET, null)).isFalse();
        assertThat(new TotpUtil().verify("", "123456")).isFalse();
    }

    @Test
    void shouldRejectIllegalBase32() {
        assertThat(new TotpUtil().verify("INVALID_CHARACTERS!", "123456")).isFalse();
    }
}
