package com.sanye.strategy.infrastructure.logging;

import net.logstash.logback.mask.ValueMasker;
import tools.jackson.core.TokenStreamContext;

/**
 * <p>
 * 凭据字段值剔除器 — logstash-logback-encoder {@link ValueMasker} 实现（规格 6.2/7.5）
 * </p>
 * <p>
 * 凭据字段（{@link SensitiveFields#isCredential}）的值永不出现在日志：
 * 替换为 {@link #placeholder(String)} 占位串——审计「发生」而非「内容」。
 * 由 logback-spring.xml 的 {@code <valueMasker>} 以无参构造实例化；
 * 静态 {@code placeholder} 供 Task 8 {@code DiffUtils} 复用（规格 6.4 单一源）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：产生端脱敏第一道防线（字段级排除）的凭据分支 + 审计 diff 凭据占位出口。</li>
 *   <li>优缺点：序列化期拦截、落盘即安全；代价为占位串是 String 而非 JSON 对象
 *       （ES 侧按文本检索可接受，换取 ValueMasker 简单返回）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public class CredentialValueMasker implements ValueMasker {

    @Override
    public Object mask(TokenStreamContext context, Object value) {
        if (value == null) {
            return null;
        }
        String fieldName = context == null ? null : context.currentName();
        if (!SensitiveFields.isCredential(fieldName)) {
            return null;
        }
        return placeholder(fieldName);
    }

    /**
     * 凭据变更占位串（ValueMasker 与审计 diff 共用，规格 6.4 单一源）
     *
     * @param fieldName 字段名
     * @return {@code {"field":X,"op":"changed"}} 占位串
     */
    public static String placeholder(String fieldName) {
        return "{\"field\":\"" + fieldName + "\",\"op\":\"changed\"}";
    }
}