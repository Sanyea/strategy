package com.sanye.strategy.infrastructure.logging;

import java.util.Locale;
import java.util.Set;

/**
 * <p>
 * 敏感字段单一源 — 凭据类后缀 + PII 类字段名（规格 6.2/6.4）
 * </p>
 * <p>
 * 日志脱敏（CredentialValueMasker/PiiValueMasker）、审计字段 diff（DiffUtils）共用本清单，
 * 修改一处同时生效，禁止在别处另维护敏感字段清单（规格第九章反模式）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：受控词表（常量类），脱敏判定唯一依据。</li>
 *   <li>优缺点：后缀匹配覆盖 passwordHash/refreshTokenHash 等组合命名；
 *       代价为误伤面（如业务字段恰以 token 结尾）——误伤时经白名单例外处理（规格 6.4），不改后缀规则。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public final class SensitiveFields {

    /**
     * 凭据类字段后缀（值剔除语义：password|secret|token|hash|salt，规格 6.2）
     */
    private static final String[] CREDENTIAL_SUFFIXES =
            {"password", "secret", "token", "hash", "salt"};

    /**
     * PII 类字段名（掩码语义：部分显示保统计，规格 6.2）
     */
    private static final Set<String> PII_FIELDS = Set.of("phone", "email", "idcard");

    private SensitiveFields() {
    }

    /**
     * 是否凭据类字段（字段名小写后按后缀匹配）
     *
     * @param fieldName JSON 字段名
     * @return true=凭据类（值剔除）
     */
    public static boolean isCredential(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String lower = fieldName.toLowerCase(Locale.ROOT);
        for (String suffix : CREDENTIAL_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否 PII 类字段（字段名小写后精确匹配）
     *
     * @param fieldName JSON 字段名
     * @return true=PII 类（部分掩码）
     */
    public static boolean isPii(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        return PII_FIELDS.contains(fieldName.toLowerCase(Locale.ROOT));
    }
}