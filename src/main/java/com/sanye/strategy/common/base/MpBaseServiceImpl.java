package com.sanye.strategy.common.base;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanye.strategy.common.model.BasePage;
import com.sanye.strategy.common.model.IBasePage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * <p>
 * MyBatis-Plus 桥接适配器 — 框架耦合的唯一位置
 * </p>
 * <p>
 * 负责将自定义抽象（{@link IWrapper}、{@link IBasePage}）转换为
 * MyBatis-Plus 原生类型（{@code QueryWrapper}、{@code Page}），
 * 并完成领域实体（{@code T}）与持久化对象（{@code P}）之间的转换。
 * 业务 Service 实现类继承此类，获得完整 CRUD 能力。
 * </p>
 * <p>
 * 类层次：
 * <pre>
 *   AbstractBaseService&lt;T&gt; → MpBaseServiceImpl&lt;P, M, T&gt; → XxxServiceImpl
 * </pre>
 * </p>
 * <p>
 * 设计说明（适配器/桥接模式）：
 * <ul>
 *   <li>角色：桥接适配器。ORM 与实体双向转换的唯一出口——Mapper 操作 PO（{@code P}），
 *       业务层面对实体（{@code T}），{@code toPO}/{@code toEntity} 由子类提供。</li>
 *   <li>优缺点：实体纯 POJO 零框架依赖，换 ORM 只重写 PO 层与本适配器；
 *       代价是每次读写多一次字段复制，且子类须实现两个转换方法。</li>
 *   <li>事务：覆写 {@code doInTransaction}，以 Spring {@link TransactionTemplate} 编程式执行，
 *       批量操作单事务内完成；替换事务管理器即可支持 JTA/Atomikos/Seata。</li>
 * </ul>
 * </p>
 * <p>
 * UML 类图（ASCII）：
 * <pre>
 * +--------------------+        +---------------------+        +--------------------------+
 * | 业务 XxxServiceImpl |        | IBaseService&lt;T&gt;      |        | AbstractBaseService&lt;T&gt;   |
 * | (toPO/toEntity)    |------&gt; | &lt;&lt;interface&gt;&gt;       |------&gt; | 模板方法 + doXXX 钩子      |
 * +--------------------+        +---------------------+        +-------------+------------+
 *                                                                           ^
 *                                                    +----------------------+--------------------+
 *                                                    | MpBaseServiceImpl&lt;P,M,T&gt;                  |
 *                                                    | 桥接适配器（唯一 MP 耦合点）                |
 *                                                    | toMpWrapper: IWrapper -&gt; QueryWrapper      |
 *                                                    | toMpPage/fromMpPage: IBasePage &lt;-&gt; Page   |
 *                                                    | toPO/toEntity: 实体 &lt;-&gt; PO（子类实现）     |
 *                                                    +---------------------+--------------------+
 *                                                                          |
 *                                                                          v
 *                                                              +---------------------+
 *                                                              | BaseMapper&lt;P&gt;        |
 *                                                              | （MP Mapper）         |
 *                                                              +---------------------+
 * </pre>
 * 时序图（ASCII，以 doList 为例）：
 * <pre>
 * Controller -> XxxServiceImpl.doList(wrapper)
 *   XxxServiceImpl -> MpBaseServiceImpl.doList(wrapper)
 *     MpBaseServiceImpl -> toMpWrapper(wrapper)     // IWrapper -> QueryWrapper
 *     MpBaseServiceImpl -> baseMapper.selectList(qw)
 *     baseMapper --> MpBaseServiceImpl: List&lt;P&gt;
 *     MpBaseServiceImpl -> toEntity(po)              // PO -> 实体
 *   XxxServiceImpl --> Controller: List&lt;T&gt;
 * </pre>
 * </p>
 *
 * @param <P> 持久化对象类型，必须继承 {@link SimpleBasePO}
 * @param <M> Mapper 类型，必须继承 {@link BaseMapper}{@code <P>}
 * @param <T> 领域实体类型，必须继承 {@link SimpleBaseEntity}
 * @author 31372
 */
public abstract class MpBaseServiceImpl<P extends SimpleBasePO, M extends BaseMapper<P>,
        T extends SimpleBaseEntity> extends AbstractBaseService<T> {

    @Autowired
    protected M baseMapper;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    // ==================== 实体 ↔ PO 转换（子类实现） ====================

    /**
     * 领域实体 → 持久化对象
     *
     * @param entity 领域实体
     * @return 持久化对象
     */
    protected abstract P toPO(T entity);

    /**
     * 持久化对象 → 领域实体
     *
     * @param po 持久化对象
     * @return 领域实体
     */
    protected abstract T toEntity(P po);

    // ==================== 事务实现（框架关注点集中于此层） ====================

    /**
     * 覆写事务钩子 — 以 Spring 编程式事务执行
     * <p>
     * 批量方法经 {@link AbstractBaseService#doInTransaction} 进入此处，
     * 任一操作失败整体回滚。事务模板 Bean 定义于 common/config 的
     * {@code TransactionConfig}（基于 {@code PlatformTransactionManager} 构建）；
     * 替换事务实现（JTA/Atomikos/Seata）只换该 Bean，或覆写本方法。
     * </p>
     */
    @Override
    protected <R> R doInTransaction(Supplier<R> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    // ==================== IWrapper → QueryWrapper 转换 ====================

    /**
     * 将自定义查询条件转换为 MyBatis-Plus QueryWrapper
     * <p>
     * 遍历条件列表，根据操作符映射到对应的 MP API；
     * {@code nested} 嵌套条件递归映射。复杂操作符的占位符参数（{@code {0}}{@code {1}}）
     * 由 MP 统一转义绑定，原始 SQL 片段由开发者提供，禁止拼接用户输入。
     * </p>
     */
    protected QueryWrapper<P> toMpWrapper(IWrapper<T> wrapper) {
        QueryWrapper<P> qw = new QueryWrapper<>();
        applyConditions(qw, wrapper.getConditions());
        return qw;
    }

    /**
     * 递归映射条件列表到 QueryWrapper（nested 子条件复用本方法）
     * <p>
     * {@code or()} 标记（{@link IQueryCondition#isOr()}）只挂在会生成 WHERE 段的
     * 条件上（基类保证）；前导 or() 之前无任何 WHERE 段，直接丢弃，防止生成
     * {@code OR a = ?} 这类非法 SQL。
     * </p>
     *
     * @param qw         当前绑定的 QueryWrapper
     * @param conditions 条件列表（可为 null）
     */
    private void applyConditions(QueryWrapper<P> qw, List<IQueryCondition> conditions) {
        if (conditions == null) {
            return;
        }
        boolean anyWhere = false;
        for (IQueryCondition cond : conditions) {
            if (cond.isOr() && anyWhere) {
                qw.or();
            }
            switch (cond.getOperator()) {
                // 比较
                case "eq"  -> qw.eq(cond.getColumn(), cond.getValue());
                case "ne"  -> qw.ne(cond.getColumn(), cond.getValue());
                case "gt"  -> qw.gt(cond.getColumn(), cond.getValue());
                case "ge"  -> qw.ge(cond.getColumn(), cond.getValue());
                case "lt"  -> qw.lt(cond.getColumn(), cond.getValue());
                case "le"  -> qw.le(cond.getColumn(), cond.getValue());
                // 模糊
                case "like"      -> qw.like(cond.getColumn(), cond.getValue());
                case "notLike"   -> qw.notLike(cond.getColumn(), cond.getValue());
                case "likeLeft"  -> qw.likeLeft(cond.getColumn(), cond.getValue());
                case "likeRight" -> qw.likeRight(cond.getColumn(), cond.getValue());
                // 范围
                case "between"    -> {
                    Object[] a = (Object[]) cond.getValue();
                    qw.between(cond.getColumn(), a[0], a[1]);
                }
                case "notBetween" -> {
                    Object[] a = (Object[]) cond.getValue();
                    qw.notBetween(cond.getColumn(), a[0], a[1]);
                }
                // 空值
                case "isNull"    -> qw.isNull(cond.getColumn());
                case "isNotNull" -> qw.isNotNull(cond.getColumn());
                // 集合
                case "in"       -> qw.in(cond.getColumn(), (Object[]) cond.getValue());
                case "notIn"    -> qw.notIn(cond.getColumn(), (Object[]) cond.getValue());
                case "inSql"    -> qw.inSql(cond.getColumn(), (String) cond.getValue());
                case "notInSql" -> qw.notInSql(cond.getColumn(), (String) cond.getValue());
                // 分组与聚合
                case "groupBy" -> qw.groupBy(Arrays.asList((String[]) cond.getValue()));
                case "having"  -> {
                    Object[] a = (Object[]) cond.getValue();
                    qw.having((String) a[0], (Object[]) a[1]);
                }
                // 排序
                case "orderByAsc"  -> qw.orderByAsc(Arrays.asList((String[]) cond.getValue()));
                case "orderByDesc" -> qw.orderByDesc(Arrays.asList((String[]) cond.getValue()));
                case "orderBy"     -> {
                    Object[] pair = (Object[]) cond.getValue();
                    String[] cols = (String[]) pair[1];
                    // 空列跳过（与 orderByAsc/orderByDesc 一致），避免 cols[0] 越界
                    if (cols.length > 0) {
                        qw.orderBy(true, (Boolean) pair[0],
                                cols[0], Arrays.copyOfRange(cols, 1, cols.length));
                    }
                }
                // ===== 复杂操作符 =====
                // and(sql, params)：原始 SQL AND，MP 无 and(String,Object...)，等价 apply
                case "and"       -> qw.apply((String) cond.getValue(), cond.getParams());
                // nested(consumer)：递归映射子条件，括号包裹
                case "nested"    -> qw.nested(w -> applyConditions(w, cond.getChildren()));
                case "apply"     -> qw.apply((String) cond.getValue(), cond.getParams());
                case "exists"    -> qw.exists((String) cond.getValue(), cond.getParams());
                case "notExists" -> qw.notExists((String) cond.getValue(), cond.getParams());
                // select：列投影，列名为数据库字段名
                case "select" -> qw.select(true, Arrays.asList((String[]) cond.getValue()));
                // last：末尾 SQL（LIMIT / FOR UPDATE 等），原样追加
                case "last" -> qw.last((String) cond.getValue());
                default -> {
                }
            }
            if (isWhereOperator(cond.getOperator())) {
                anyWhere = true;
            }
        }
    }

    /** 会生成 WHERE 段的操作符（与 AbstractWrapper 写入端双份维护） */
    private static final java.util.Set<String> WHERE_OPERATORS = java.util.Set.of(
            "eq", "ne", "gt", "ge", "lt", "le",
            "like", "notLike", "likeLeft", "likeRight",
            "between", "notBetween",
            "isNull", "isNotNull",
            "in", "notIn", "inSql", "notInSql",
            "and", "apply", "exists", "notExists", "nested");

    private static boolean isWhereOperator(String op) {
        return WHERE_OPERATORS.contains(op);
    }

    /**
     * IBasePage → MyBatis-Plus Page 转换
     */
    protected Page<P> toMpPage(IBasePage<T> page) {
        return new Page<>(page.getCurrent(), page.getSize(), page.searchCount());
    }

    /**
     * MyBatis-Plus IPage → IBasePage 回填
     */
    protected IBasePage<P> fromMpPage(Page<P> mpPage) {
        BasePage<P> result = new BasePage<>();
        result.setRecords(mpPage.getRecords());
        result.setTotal(mpPage.getTotal());
        result.setSize(mpPage.getSize());
        result.setCurrent(mpPage.getCurrent());
        return result;
    }

    // ==================== doXxx 实现 — 实体↔PO 转换后委托给 baseMapper ====================

    @Override
    protected boolean doInsert(T entity) {
        return baseMapper.insert(toPO(entity)) > 0;
    }

    @Override
    protected boolean doUpdateById(T entity) {
        return baseMapper.updateById(toPO(entity)) > 0;
    }

    @Override
    protected boolean doDeleteById(Serializable id) {
        return baseMapper.deleteById(id) > 0;
    }

    @Override
    protected T doGetById(Serializable id) {
        return toEntity(baseMapper.selectById(id));
    }

    @Override
    protected List<T> doListByIds(Collection<? extends Serializable> ids) {
        return baseMapper.selectBatchIds(ids).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    protected T doGetOne(IWrapper<T> queryWrapper) {
        QueryWrapper<P> qw = toMpWrapper(queryWrapper);
        // 追加 LIMIT 1，避免多行时 selectOne 抛 TooManyResultsException → 500；
        // 调用方已显式使用 last()（如 FOR UPDATE）时不再追加，避免尾部 SQL 冲突
        boolean hasTail = queryWrapper.getConditions().stream()
                .anyMatch(c -> "last".equals(c.getOperator()));
        if (!hasTail) {
            qw.last("LIMIT 1");
        }
        return toEntity(baseMapper.selectOne(qw));
    }

    @Override
    protected List<T> doList(IWrapper<T> queryWrapper) {
        return baseMapper.selectList(toMpWrapper(queryWrapper)).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    protected long doCount(IWrapper<T> queryWrapper) {
        return baseMapper.selectCount(toMpWrapper(queryWrapper));
    }

    @Override
    protected IBasePage<T> doPage(IBasePage<T> page, IWrapper<T> queryWrapper) {
        Page<P> mpResult = baseMapper.selectPage(toMpPage(page), toMpWrapper(queryWrapper));
        return fromMpPage(mpResult).convert(this::toEntity);
    }

    @Override
    protected boolean doSaveOrUpdate(T entity) {
        // BaseMapper 无 saveOrUpdate，按主键是否存在判断：存在则更新，不存在则新增
        P po = toPO(entity);
        if (po.getId() != null) {
            return baseMapper.updateById(po) > 0;
        }
        return baseMapper.insert(po) > 0;
    }

    @Override
    protected int doInsertBatch(Collection<T> entityList) {
        if (entityList == null || entityList.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (T entity : entityList) {
            affected += baseMapper.insert(toPO(entity)) > 0 ? 1 : 0;
        }
        return affected;
    }

    @Override
    protected int doUpdateBatch(Collection<T> entityList) {
        if (entityList == null || entityList.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (T entity : entityList) {
            affected += baseMapper.updateById(toPO(entity)) > 0 ? 1 : 0;
        }
        return affected;
    }

    @Override
    protected int doDeleteBatch(Collection<? extends Serializable> idList) {
        if (idList == null || idList.isEmpty()) {
            return 0;
        }
        return baseMapper.deleteByIds(idList);
    }

    @Override
    protected int doSaveOrUpdateBatch(Collection<T> entityList) {
        if (entityList == null || entityList.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (T entity : entityList) {
            affected += doSaveOrUpdate(entity) ? 1 : 0;
        }
        return affected;
    }
}
