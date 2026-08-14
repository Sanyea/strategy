package com.sanye.strategy.infrastructure.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PermissionCodeValidatorTest {
    @Test void acceptsStandardThreeSegment() {
        assertDoesNotThrow(() -> PermissionCodeValidator.validate("system:role:manage"));
    }
    @Test void acceptsMultiLevelResource() {
        assertDoesNotThrow(() -> PermissionCodeValidator.validate("system:rbac:debug:manage"));
    }
    @Test void acceptsNestedResourceModule() {
        // sys:tem:role:manage = 模块 sys + 资源 tem:role + 操作 manage，按段校验合法（资源可多级冒号分隔）
        assertDoesNotThrow(() -> PermissionCodeValidator.validate("sys:tem:role:manage"));
    }
    @Test void rejectsTwoSegments() {
        assertThrows(IllegalArgumentException.class, () -> PermissionCodeValidator.validate("system:manage"));
    }
    @Test void rejectsEmptySegment() {
        assertThrows(IllegalArgumentException.class, () -> PermissionCodeValidator.validate("system::manage"));
    }
    @Test void rejectsTrailingColonEmptySegment() {
        // 尾部冒号产生空操作段，split(":", -1) 保留空串必须被拒（无 -1 会吞尾部空段放行）
        assertThrows(IllegalArgumentException.class, () -> PermissionCodeValidator.validate("system:role:manage:"));
    }
    @Test void rejectsUppercaseOrSlash() {
        assertThrows(IllegalArgumentException.class, () -> PermissionCodeValidator.validate("System:role:manage"));
        assertThrows(IllegalArgumentException.class, () -> PermissionCodeValidator.validate("system/role/manage"));
    }
}
