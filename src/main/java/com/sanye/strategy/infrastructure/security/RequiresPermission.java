package com.sanye.strategy.infrastructure.security;

import java.lang.annotation.*;

/** 接口鉴权注解 — value 为三段式权限码（模块:资源:操作），方法级优先类级兜底 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {
    String value();
}
