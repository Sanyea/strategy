package com.sanye.strategy.infrastructure.logging;

import org.junit.jupiter.api.Test;
import tools.jackson.core.TokenStreamContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PiiValueMasker 单测 — phone/email/idCard 部分掩码保统计；非 PII 字段与畸形值放行
 * <p>同 CredentialValueMaskerTest：mock context 提供字段名（字段名驱动）。</p>
 */
class PiiValueMaskerTest {

    private final PiiValueMasker masker = new PiiValueMasker();

    private static TokenStreamContext ctx(String fieldName) {
        TokenStreamContext ctx = mock(TokenStreamContext.class);
        when(ctx.currentName()).thenReturn(fieldName);
        return ctx;
    }

    @Test
    void masksPhoneMiddleSegments() {
        assertEquals("138****5678", masker.mask(ctx("phone"), "13812345678"));
    }

    @Test
    void masksEmailKeepingPrefixAndDomain() {
        assertEquals("ab***@example.com", masker.mask(ctx("email"), "abcdef@example.com"));
        // 短前缀（<2 位可保留时）保留全部前缀位
        assertEquals("ab***@x.cn", masker.mask(ctx("email"), "ab@x.cn"));
    }

    @Test
    void masksIdCardKeepingHeadAndTail() {
        assertEquals("110101********1234", masker.mask(ctx("idCard"), "110101199003071234"));
    }

    @Test
    void passesThroughNonPiiFields() {
        assertNull(masker.mask(ctx("username"), "13812345678"));
        assertNull(masker.mask(null, "13812345678"));
    }

    @Test
    void passesThroughMalformedValues() {
        // 畸形值宁可不掩（保可用性），兜底靠传输端 PII 正则（阶段1）
        assertEquals("123", masker.mask(ctx("phone"), "123"));
        assertEquals("short", masker.mask(ctx("email"), "short"));
        assertEquals("no-at-sign", masker.mask(ctx("email"), "no-at-sign"));
    }
}