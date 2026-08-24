package com.sanye.strategy.infrastructure.logging;

import org.junit.jupiter.api.Test;
import tools.jackson.core.TokenStreamContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CredentialValueMasker 单测 — 凭据字段值剔除（changed 占位），非凭据字段放行
 * <p>ValueMasker 字段名经 {@link TokenStreamContext#currentName()} 注入（字段名驱动），
 * 测试用 Mockito mock context 提供字段名；null context（字段名未知）放行。</p>
 */
class CredentialValueMaskerTest {

    private final CredentialValueMasker masker = new CredentialValueMasker();

    private static TokenStreamContext ctx(String fieldName) {
        TokenStreamContext ctx = mock(TokenStreamContext.class);
        when(ctx.currentName()).thenReturn(fieldName);
        return ctx;
    }

    @Test
    void masksCredentialFieldsWithChangedPlaceholder() {
        assertEquals("{\"field\":\"password\",\"op\":\"changed\"}",
                masker.mask(ctx("password"), "P@ssw0rd123"));
        assertEquals("{\"field\":\"refreshTokenHash\",\"op\":\"changed\"}",
                masker.mask(ctx("refreshTokenHash"), "abc123hash"));
        assertEquals("{\"field\":\"mfaSecret\",\"op\":\"changed\"}",
                masker.mask(ctx("mfaSecret"), "JBSWY3DPEHPK3PXP"));
        assertEquals("{\"field\":\"salt\",\"op\":\"changed\"}",
                masker.mask(ctx("salt"), "random-salt"));
    }

    @Test
    void ignoresNonCredentialFields() {
        assertNull(masker.mask(ctx("username"), "user1"));
        assertNull(masker.mask(ctx("roleName"), "运营"));
    }

    @Test
    void ignoresNullValue() {
        assertNull(masker.mask(ctx("password"), null));
    }

    @Test
    void ignoresUnknownFieldNameWithoutContext() {
        assertNull(masker.mask(null, "P@ssw0rd123"));
    }
}