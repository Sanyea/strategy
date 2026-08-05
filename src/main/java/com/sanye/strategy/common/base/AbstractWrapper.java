package com.sanye.strategy.common.base;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>
 * 查询条件存储基类 — 维护 {@code List<IQueryCondition>}，与 ORM 框架无关
 * </p>
 * <p>
 * 普通条件由子类 {@link DefaultQueryWrapper} 经 {@link #addCondition} 写入；
 * 复杂逻辑（or/and/nested/apply/exists/notExists/select/last）在此实现。
 * 空/非法输入在构建期 fail-fast（抛 {@link IllegalArgumentException}），
 * 避免静默生成错误 SQL。
 * </p>
 * <p>
 * 类层次：
 * <pre>
 * IWrapper&lt;T&gt;                        接口
 *   └─ AbstractWrapper&lt;T&gt;            条件存储基类（本类，零框架依赖）
 *        └─ DefaultQueryWrapper&lt;T&gt;   基础查询实现
 * </pre>
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：查询构建器抽象基类，条件列表唯一写入入口；{@code or()} 标记挂载到
 *       下一条会生成 WHERE 段的条件上（前后缀 or() 自动消除），空嵌套组与非法
 *       SQL 片段构建期报错。</li>
 *   <li>优缺点：复杂操作符集中一处，输入校验前置、失败快速可见，杜绝静默错误 SQL；
 *       SQL 片段参数用 {@code {0}{1}} 占位符由桥接层交给 MP 转义，避免注入。
 *       缺点：操作符字符串与桥接层映射为双份维护，新增操作符需两端同步。</li>
 *   <li>注意：{@code and}/{@code apply}/{@code exists}/{@code notExists} 的
 *       {@code {n}} 占位符必须配齐参数；{@code last}/{@code apply} 等为原始 SQL，
 *       禁止拼接用户输入。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public abstract class AbstractWrapper<T> implements IWrapper<T> {

    /** 占位符 {n} 匹配 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(\\d+)}");

    /** 会生成 WHERE 段的操作符：or 标记只挂载到这些操作符，非 WHERE 段操作符忽略 or */
    private static final Set<String> WHERE_OPS = Set.of(
            "eq", "ne", "gt", "ge", "lt", "le",
            "like", "notLike", "likeLeft", "likeRight",
            "between", "notBetween",
            "isNull", "isNotNull",
            "in", "notIn", "inSql", "notInSql",
            "and", "apply", "exists", "notExists", "nested");

    protected final List<IQueryCondition> conditionList = new ArrayList<>();

    /** 待挂载的 or 标记：or() 设置，下一条 WHERE 段条件消费 */
    private boolean pendingOr;

    /**
     * 内部统一的条件实现
     */
    protected static class Condition implements IQueryCondition {
        private final String column;
        private final String op;
        private final Object value;
        private final Object[] params;
        private final List<IQueryCondition> children;
        private final boolean or;

        public Condition(String column, String op, Object value) {
            this(column, op, value, null, null, false);
        }

        public Condition(String column, String op, Object value, Object[] params,
                         List<IQueryCondition> children) {
            this(column, op, value, params, children, false);
        }

        public Condition(String column, String op, Object value, Object[] params,
                         List<IQueryCondition> children, boolean or) {
            this.column = column;
            this.op = op;
            this.value = value;
            this.params = params;
            this.children = children;
            this.or = or;
        }

        @Override
        public String getColumn() {
            return column;
        }

        @Override
        public String getOperator() {
            return op;
        }

        @Override
        public Object getValue() {
            return value;
        }

        @Override
        public Object[] getParams() {
            return params;
        }

        @Override
        public List<IQueryCondition> getChildren() {
            return children;
        }

        @Override
        public boolean isOr() {
            return or;
        }
    }

    /**
     * 通用添加条件方法（子类直接调用），消费待挂载的 or 标记
     */
    protected IWrapper<T> addCondition(String column, String op, Object value) {
        return addCondition(column, op, value, null, null);
    }

    /**
     * 通用添加条件方法（带附加参数与子条件），消费待挂载的 or 标记
     * <p>
     * or 标记只挂载到会生成 WHERE 段的条件；非 WHERE 段操作符
     * （select/groupBy/having/orderByAsc/orderByDesc/orderBy/last）忽略标记并清空待挂载状态。
     * </p>
     */
    protected IWrapper<T> addCondition(String column, String op, Object value, Object[] params,
                                       List<IQueryCondition> children) {
        boolean or = pendingOr && WHERE_OPS.contains(op);
        pendingOr = false;
        conditionList.add(new Condition(column, op, value, params, children, or));
        return this;
    }

    /**
     * 子查询构建工厂 — 可覆写以支持自定义条件实现，保持抽象层与具体实现解耦
     *
     * @return 子查询构建器
     */
    protected IWrapper<T> newWrapper() {
        return new DefaultQueryWrapper<>();
    }

    /**
     * 统一返回条件
     */
    @Override
    public List<IQueryCondition> getConditions() {
        return conditionList;
    }

    // ==================== 复杂逻辑（or/and/nested/apply/exists/notExists/select/last） ====================

    /**
     * 逻辑 OR — 标记下一条 WHERE 段条件为 OR 拼接
     * <p>
     * 实现为待挂载标记：不生成独立条件，由下一条会生成 WHERE 段的条件消费。
     * 前导 or()（无前置 WHERE 段）、末尾 or()（无后续条件）、or() 位于非 WHERE 段
     * 操作符之前，均自动消除，不会产生非法 SQL。
     * </p>
     */
    @Override
    public IWrapper<T> or() {
        pendingOr = true;
        return this;
    }

    /**
     * 逻辑 AND — 追加原始 SQL（{@code {n}} 占位符绑定参数）
     *
     * @throws IllegalArgumentException SQL 片段为空或占位符缺少对应参数
     */
    @Override
    public IWrapper<T> and(String sqlAnd, Object... params) {
        validateRawSql("and", sqlAnd, params);
        return addCondition(null, "and", sqlAnd, params, null);
    }

    /**
     * 嵌套条件组 — 括号包裹，内部可含 {@code or()}
     * <p>
     * 子组无任何 WHERE 段条件（空 consumer / 仅调用 {@code or()} / 仅投影 select）时
     * 整组忽略，避免生成 {@code ()} 非法 SQL。
     * </p>
     */
    @Override
    public IWrapper<T> nested(Consumer<IWrapper<T>> consumer) {
        if (consumer == null) {
            return this;
        }
        IWrapper<T> sub = newWrapper();
        consumer.accept(sub);
        boolean hasWhere = sub.getConditions().stream()
                .anyMatch(c -> WHERE_OPS.contains(c.getOperator()));
        if (!hasWhere) {
            return this;
        }
        return addCondition(null, "nested", null, null, sub.getConditions());
    }

    /**
     * 追加原始 SQL（{@code {n}} 占位符绑定参数）
     *
     * @throws IllegalArgumentException SQL 片段为空或占位符缺少对应参数
     */
    @Override
    public IWrapper<T> apply(String applySql, Object... params) {
        validateRawSql("apply", applySql, params);
        return addCondition(null, "apply", applySql, params, null);
    }

    /**
     * EXISTS 子查询（{@code {n}} 占位符绑定参数）
     *
     * @throws IllegalArgumentException SQL 片段为空或占位符缺少对应参数
     */
    @Override
    public IWrapper<T> exists(String existsSql, Object... params) {
        validateRawSql("exists", existsSql, params);
        return addCondition(null, "exists", existsSql, params, null);
    }

    /**
     * NOT EXISTS 子查询（{@code {n}} 占位符绑定参数）
     *
     * @throws IllegalArgumentException SQL 片段为空或占位符缺少对应参数
     */
    @Override
    public IWrapper<T> notExists(String notExistsSql, Object... params) {
        validateRawSql("notExists", notExistsSql, params);
        return addCondition(null, "notExists", notExistsSql, params, null);
    }

    /**
     * 列投影 — 限制查询返回列，可排除敏感字段（如密码、token）
     * <p>
     * 列名为数据库字段名（与实体驼峰字段不同）。空投影会被 MP 静默丢弃并退化为
     * 查询全部列（含敏感字段），故为空时直接报错。
     * </p>
     *
     * @throws IllegalArgumentException 未指定任何列名
     */
    @Override
    public IWrapper<T> select(String... columns) {
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException(
                    "select 必须指定至少一个列名：空投影会退化为查询全部列（含敏感字段）");
        }
        return addCondition(null, "select", columns, null, null);
    }

    /**
     * 追加末尾 SQL（如 LIMIT 1）
     * <p>
     * 原样追加，仅用于开发者可控的固定片段（如 LIMIT/FOR UPDATE），
     * 禁止拼接用户输入，否则存在 SQL 注入风险。
     * </p>
     *
     * @throws IllegalArgumentException SQL 片段为空
     */
    @Override
    public IWrapper<T> last(String lastSql) {
        if (lastSql == null || lastSql.isBlank()) {
            throw new IllegalArgumentException("last SQL 片段不能为空");
        }
        return addCondition(null, "last", lastSql, null, null);
    }

    /**
     * 校验原始 SQL 片段：非空，且 {@code {n}} 占位符必须有对应参数
     */
    private void validateRawSql(String op, String sql, Object... params) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException(op + " 的 SQL 片段不能为空");
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(sql);
        int maxIndex = -1;
        while (matcher.find()) {
            maxIndex = Math.max(maxIndex, Integer.parseInt(matcher.group(1)));
        }
        if (maxIndex >= 0 && (params == null || params.length <= maxIndex)) {
            throw new IllegalArgumentException(
                    op + " 的占位符 {0.." + maxIndex + "} 缺少对应参数: " + sql);
        }
    }
}
