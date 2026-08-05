package com.sanye.strategy.common.base;

import java.util.List;

/**
 * @author 31372
 */

public interface IWrapper<T> {

    // ========== 比较 ==========
    IWrapper<T> eq(String column, Object value);
    IWrapper<T> ne(String column, Object value);
    IWrapper<T> gt(String column, Object value);
    IWrapper<T> ge(String column, Object value);
    IWrapper<T> lt(String column, Object value);
    IWrapper<T> le(String column, Object value);

    // ========== 模糊匹配 ==========
    IWrapper<T> like(String column, Object value);
    IWrapper<T> notLike(String column, Object value);
    IWrapper<T> likeLeft(String column, Object value);
    IWrapper<T> likeRight(String column, Object value);

    // ========== 范围 ==========
    IWrapper<T> between(String column, Object val1, Object val2);
    IWrapper<T> notBetween(String column, Object val1, Object val2);

    // ========== 空值 ==========
    IWrapper<T> isNull(String column);
    IWrapper<T> isNotNull(String column);

    // ========== 集合 ==========
    IWrapper<T> in(String column, Object... values);
    IWrapper<T> notIn(String column, Object... values);
    IWrapper<T> inSql(String column, String sql);
    IWrapper<T> notInSql(String column, String sql);

    // ========== 分组与聚合 ==========
    IWrapper<T> groupBy(String... columns);
    IWrapper<T> having(String sqlHaving, Object... params);

    // ========== 排序 ==========
    IWrapper<T> orderByAsc(String... columns);
    IWrapper<T> orderByDesc(String... columns);
    IWrapper<T> orderBy(boolean asc, String... columns);

    // ========== 逻辑拼接 ==========
    IWrapper<T> or();
    IWrapper<T> and(String sqlAnd, Object... params);
    IWrapper<T> nested(java.util.function.Consumer<IWrapper<T>> consumer);
    IWrapper<T> apply(String applySql, Object... params);

    // ========== 存在性 ==========
    IWrapper<T> exists(String existsSql, Object... params);
    IWrapper<T> notExists(String notExistsSql, Object... params);

    // ========== 其他 ==========
    IWrapper<T> select(String... columns);
    IWrapper<T> last(String lastSql);

    // 获取所有已构建的条件（保留原有方法）
    List<IQueryCondition> getConditions();
}
