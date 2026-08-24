package com.sanye.strategy.infrastructure.logging;

import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <p>
 * 审计字段 diff 工具 — 纯 POJO 反射 diff + 关联集 diff + JSON 序列化（规格 7.1/7.5）
 * </p>
 * <p>
 * 门面就地 diff：{@code getById} 已有旧值，变更前拍快照（{@code BeanCopyUtils.copy}），
 * 变更后调用 {@link #diffBean} 产出「只报 new 非 null 且与 old 不同字段」的结构化条目，
 * 经 {@link #toChangeDiffJson} 序列化落 {@code ums_oper_log.change_diff}。
 * 敏感字段沿用 {@link SensitiveFields} 单一源（规格 6.4）：凭据类记
 * {@code {"field":X,"op":"changed"}} 变更事实（规格 7.5 值永不出现）、PII 类掩码保统计。
 * 关联集 diff（{@link #diffIdSet}）供物理删除关联表（无行快照）变更前后集合比对。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：审计字段 diff 的纯函数工具（无框架依赖，静态方法），RBAC 门面与后续业务域共用。</li>
 *   <li>优缺点：零状态零依赖、规则收口一处；代价为反射性能开销（审计低频可接受）、
 *       复杂嵌套对象不支持（值以 {@code toString} 归一，嵌套结构由调用方预展平）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public final class DiffUtils {

    /**
     * diff JSON 序列化器 — Boot4 默认 Jackson 3（tools.jackson）；序列化异常为非受检，无需 try/catch
     */
    private static final ObjectMapper DIFF_MAPPER = new ObjectMapper();

    /**
     * 忽略字段：主键 + 审计字段（规格 7.1）
     */
    private static final Set<String> IGNORED_FIELDS =
            Set.of("id", "createTime", "updateTime", "createUserId", "updateUserId", "deleted");

    private DiffUtils() {
    }

    /**
     * 纯 POJO 字段 diff：反射遍历（含继承链），只报 new 非 null 且与 old 不同的字段
     * <p>凭据类字段值永不出现（changed 占位）；PII 类字段掩码保统计；枚举经 {@code getCode()} 落码值。</p>
     *
     * @param oldValue 变更前快照（非 null）
     * @param newValue 变更后实体（非 null）
     * @return diff 条目列表（无变更返回空列表）
     */
    public static List<Map<String, Object>> diffBean(Object oldValue, Object newValue) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (oldValue == null || newValue == null) {
            return entries;
        }
        for (Field field : fieldsInChain(newValue.getClass())) {
            if (Modifier.isStatic(field.getModifiers()) || IGNORED_FIELDS.contains(field.getName())) {
                continue;
            }
            Object oldField;
            Object newField;
            try {
                field.setAccessible(true);
                oldField = field.get(oldValue);
                newField = field.get(newValue);
            } catch (IllegalAccessException e) {
                // 反射不可达字段跳过（防御），不影响审计主流程
                continue;
            }
            if (newField == null) {
                continue;   // new null = 部分更新未涉及，不报（规格 7.1）
            }
            if (SensitiveFields.isCredential(field.getName())) {
                // 凭据类：值永不出现，只记变更事实（规格 7.5）
                if (!Objects.equals(oldField, newField)) {
                    entries.add(credentialEntry(field.getName()));
                }
                continue;
            }
            String oldText = normalize(oldField);
            String newText = normalize(newField);
            if (SensitiveFields.isPii(field.getName())) {
                // PII 类：掩码保统计（规格 6.2/7.5）
                String maskedOld = PiiValueMasker.maskValue(field.getName(), oldText);
                String maskedNew = PiiValueMasker.maskValue(field.getName(), newText);
                oldText = maskedOld != null ? maskedOld : oldText;
                newText = maskedNew != null ? maskedNew : newText;
            }
            if (Objects.equals(oldText, newText)) {
                continue;   // 未变化不报
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", field.getName());
            entry.put("old", oldText);
            entry.put("new", newText);
            entries.add(entry);
        }
        return entries;
    }

    /**
     * 关联集 diff（物理删除关联表无行快照）：before vs after → added/removed
     *
     * @param field  集合字段标签（如 roleIds/permissionIds）
     * @param before 变更前 ID 集合
     * @param after  变更后 ID 集合
     * @return {@code {"field":X,"op":"add"|"remove","ids":[...]}} 条目列表（无变化返回空列表）
     */
    public static List<Map<String, Object>> diffIdSet(String field, Set<Long> before, Set<Long> after) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Set<Long> beforeSafe = before == null ? Set.of() : before;
        Set<Long> afterSafe = after == null ? Set.of() : after;
        List<Long> added = new ArrayList<>(afterSafe);
        added.removeAll(beforeSafe);
        List<Long> removed = new ArrayList<>(beforeSafe);
        removed.removeAll(afterSafe);
        if (!added.isEmpty()) {
            entries.add(idSetEntry(field, "add", added));
        }
        if (!removed.isEmpty()) {
            entries.add(idSetEntry(field, "remove", removed));
        }
        return entries;
    }

    /**
     * diff 条目 → JSON 数组字符串（change_diff 落库形态，规格 7.1）；
     * 空/null 条目返回 null（DB 落默认值）
     *
     * @param entries diff 条目列表
     * @return JSON 数组字符串；无变更返回 null
     */
    public static String toChangeDiffJson(List<Map<String, Object>> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return DIFF_MAPPER.writeValueAsString(entries);
    }

    private static Map<String, Object> credentialEntry(String fieldName) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("field", fieldName);
        entry.put("op", "changed");
        return entry;
    }

    private static Map<String, Object> idSetEntry(String field, String op, List<Long> ids) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("field", field);
        entry.put("op", op);
        entry.put("ids", ids);
        return entry;
    }

    private static List<Field> fieldsInChain(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                fields.add(f);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static String normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> enumValue) {
            // 业务枚举统一取 getCode() 落码值（DataScopeEnum/RoleStatusEnum/PermissionTypeEnum 等）
            try {
                Method getCode = value.getClass().getMethod("getCode");
                Object code = getCode.invoke(value);
                return code == null ? null : String.valueOf(code);
            } catch (ReflectiveOperationException e) {
                return enumValue.name();
            }
        }
        return value.toString();
    }
}