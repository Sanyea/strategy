package com.sanye.strategy.infrastructure.security;

import java.util.regex.Pattern;

/** 权限码语法校验（按段）— 模块/操作 `[a-z][a-z0-9-]+` 禁冒号，资源段可多级冒号分隔逐段非空 */
public final class PermissionCodeValidator {
    private static final Pattern SEGMENT = Pattern.compile("[a-z][a-z0-9-]+");

    private PermissionCodeValidator() {}

    /** 校验三段式权限码；不合规抛 IllegalArgumentException（扫描 fail-fast） */
    public static void validate(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("权限码不能为空");
        }
        // split(":", -1) 保留尾部空段——"system:role:manage:" 尾随冒号须被拒（split 无 -1 会吞尾部空串放行）
        String[] parts = code.split(":", -1);
        if (parts.length < 3) {
            throw new IllegalArgumentException("权限码至少三段 模块:资源:操作: " + code);
        }
        if (!SEGMENT.matcher(parts[0]).matches()) {
            throw new IllegalArgumentException("权限码模块段非法(仅小写字母数字连字符，禁冒号): " + code);
        }
        for (int i = 1; i < parts.length - 1; i++) {
            if (!SEGMENT.matcher(parts[i]).matches()) {
                throw new IllegalArgumentException("权限码资源段非法: " + code);
            }
        }
        if (!SEGMENT.matcher(parts[parts.length - 1]).matches()) {
            throw new IllegalArgumentException("权限码操作段非法: " + code);
        }
    }
}
