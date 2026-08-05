package com.sanye.strategy.common.base;

import java.util.Collection;

/**
 * <p>
 * 基础查询条件实现 — 比较/模糊/范围/空值/集合/分组/排序
 * </p>
 * <p>
 * 值校验约定（构建期）：
 * <ul>
 *   <li>比较（eq/ne/gt/ge/lt/le）与模糊（like/notLike/likeLeft/likeRight）传 null
 *       视为"未提供"，条件跳过——避免生成 {@code column = NULL} 永不匹配，动态查询自然工作。</li>
 *   <li>{@code in}/{@code notIn} 传单个 {@link Collection} 时自动展平为数组，
 *       避免 List 被当作单元素绑定为标量。</li>
 * </ul>
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：查询构建器具体实现，补充普通条件的值形状（哨兵列名、Object[] 组合），
 *       复杂逻辑（or/and/nested/apply/exists/select/last）由 {@link AbstractWrapper} 提供。</li>
 *   <li>优缺点：null 语义与 MP 条件重载 {@code eq(boolean, ...)} 对齐，调用方无需手写判空；
 *       代价是"忘记判空导致放宽过滤"这一静默风险需靠文档与代码审查约束。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public class DefaultQueryWrapper<T> extends AbstractWrapper<T> {

    // ========== 比较（null 视为未提供，跳过） ==========

    @Override
    public IWrapper<T> eq(String column, Object value) {
        return addIfNotNull(column, "eq", value);
    }

    @Override
    public IWrapper<T> ne(String column, Object value) {
        return addIfNotNull(column, "ne", value);
    }

    @Override
    public IWrapper<T> gt(String column, Object value) {
        return addIfNotNull(column, "gt", value);
    }

    @Override
    public IWrapper<T> ge(String column, Object value) {
        return addIfNotNull(column, "ge", value);
    }

    @Override
    public IWrapper<T> lt(String column, Object value) {
        return addIfNotNull(column, "lt", value);
    }

    @Override
    public IWrapper<T> le(String column, Object value) {
        return addIfNotNull(column, "le", value);
    }

    // ========== 模糊匹配（null 视为未提供，跳过） ==========

    @Override
    public IWrapper<T> like(String column, Object value) {
        return addIfNotNull(column, "like", value);
    }

    @Override
    public IWrapper<T> notLike(String column, Object value) {
        return addIfNotNull(column, "notLike", value);
    }

    @Override
    public IWrapper<T> likeLeft(String column, Object value) {
        return addIfNotNull(column, "likeLeft", value);
    }

    @Override
    public IWrapper<T> likeRight(String column, Object value) {
        return addIfNotNull(column, "likeRight", value);
    }

    // ========== 范围 ==========

    @Override
    public IWrapper<T> between(String column, Object val1, Object val2) {
        return addCondition(column, "between", new Object[]{val1, val2});
    }

    @Override
    public IWrapper<T> notBetween(String column, Object val1, Object val2) {
        return addCondition(column, "notBetween", new Object[]{val1, val2});
    }

    // ========== 空值 ==========

    @Override
    public IWrapper<T> isNull(String column) {
        return addCondition(column, "isNull", null);
    }

    @Override
    public IWrapper<T> isNotNull(String column) {
        return addCondition(column, "isNotNull", null);
    }

    // ========== 集合（单 Collection 参数自动展平） ==========

    @Override
    public IWrapper<T> in(String column, Object... values) {
        return addCondition(column, "in", flattenCollection(values));
    }

    @Override
    public IWrapper<T> notIn(String column, Object... values) {
        return addCondition(column, "notIn", flattenCollection(values));
    }

    @Override
    public IWrapper<T> inSql(String column, String sql) {
        return addCondition(column, "inSql", sql);
    }

    @Override
    public IWrapper<T> notInSql(String column, String sql) {
        return addCondition(column, "notInSql", sql);
    }

    // ========== 分组与聚合 ==========

    @Override
    public IWrapper<T> groupBy(String... columns) {
        return addCondition("groupBy", "groupBy", columns);
    }

    @Override
    public IWrapper<T> having(String sqlHaving, Object... params) {
        return addCondition("having", "having", new Object[]{sqlHaving, params});
    }

    // ========== 排序 ==========

    @Override
    public IWrapper<T> orderByAsc(String... columns) {
        return addCondition("orderByAsc", "orderByAsc", columns);
    }

    @Override
    public IWrapper<T> orderByDesc(String... columns) {
        return addCondition("orderByDesc", "orderByDesc", columns);
    }

    @Override
    public IWrapper<T> orderBy(boolean asc, String... columns) {
        return addCondition("orderBy", "orderBy", new Object[]{asc, columns});
    }

    // ========== 私有辅助 ==========

    /**
     * null 值视为未提供，跳过该条件（避免生成 {@code column = NULL} 永不匹配）
     */
    private IWrapper<T> addIfNotNull(String column, String op, Object value) {
        if (value == null) {
            return this;
        }
        return addCondition(column, op, value);
    }

    /**
     * 单个 {@link Collection} 参数展平为数组，避免 List 被当作单元素绑定为标量
     *
     * @param values varargs 参数
     * @return 展平后的数组（非 Collection 场景原样返回）
     */
    private Object[] flattenCollection(Object[] values) {
        if (values != null && values.length == 1 && values[0] instanceof Collection<?> collection) {
            return collection.toArray();
        }
        return values;
    }
}
