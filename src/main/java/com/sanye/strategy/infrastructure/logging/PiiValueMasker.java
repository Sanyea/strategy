package com.sanye.strategy.infrastructure.logging;

import net.logstash.logback.mask.ValueMasker;
import tools.jackson.core.TokenStreamContext;

import java.util.Locale;

/**
 * <p>
 * PII 字段掩码器 — logstash-logback-encoder {@link ValueMasker} 实现（规格 6.2）
 * </p>
 * <p>
 * phone/email/idCard 部分显示保统计：手机号掩中段（138****5678）、邮箱保首 2 位与域名、
 * 身份证保前 6 后 4。畸形值（长度/格式不符）原样放行——宁可不掩不误删，
 * 兜底靠传输端 PII 正则（阶段1，规格 6.1 第二道）。
 * 静态 {@code maskValue} 供 Task 8 {@code DiffUtils} 复用（规格 6.4 单一源）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：产生端脱敏第一道防线的 PII 分支 + 审计 diff PII 掩码出口。</li>
 *   <li>优缺点：保统计可用性（按网段/域名聚合不受影响）；代价为规则仅覆盖标准形态。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public class PiiValueMasker implements ValueMasker {

    private static final int PHONE_LENGTH = 11;
    private static final int ID_CARD_LENGTH = 18;
    private static final int EMAIL_MIN_PREFIX_LENGTH = 3;

    @Override
    public Object mask(TokenStreamContext context, Object value) {
        if (!(value instanceof CharSequence chars)) {
            return null;
        }
        String fieldName = context == null ? null : context.currentName();
        return maskValue(fieldName, chars.toString());
    }

    /**
     * 按字段名掩码 PII 值（ValueMasker 与审计 diff 共用，规格 6.4 单一源）；
     * 非 PII 字段返回 null（原样放行），畸形值返回原文（宁可不掩不误删）
     *
     * @param fieldName 字段名
     * @param text      原始文本
     * @return 掩码后文本；非 PII 字段返回 null
     */
    public static String maskValue(String fieldName, String text) {
        if (!SensitiveFields.isPii(fieldName)) {
            return null;
        }
        String lower = fieldName.toLowerCase(Locale.ROOT);
        if ("phone".equals(lower)) {
            return maskPhone(text);
        }
        if ("email".equals(lower)) {
            return maskEmail(text);
        }
        return maskIdCard(text);
    }

    private static String maskPhone(String phone) {
        if (phone.length() != PHONE_LENGTH) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at < 2) {
            return email;
        }
        int keep = Math.min(2, at);
        return email.substring(0, keep) + "***" + email.substring(at);
    }

    private static String maskIdCard(String idCard) {
        if (idCard.length() != ID_CARD_LENGTH) {
            return idCard;
        }
        return idCard.substring(0, 6) + "********" + idCard.substring(14);
    }
}