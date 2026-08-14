package com.sanye.strategy.infrastructure.security;

import java.lang.annotation.*;

/** 显式豁免鉴权注解 — 标注类/方法跳过权限校验（仅需登录），不硬编码路径 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface NoPermissionRequired {}
