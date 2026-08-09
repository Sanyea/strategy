package com.sanye.strategy.common.base;

import com.sanye.strategy.common.model.IBasePage;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * <p>
 * 抽象 Service 基类 — 模板方法骨架
 * </p>
 * <p>
 * 纯 POJO 抽象类，零框架依赖（不依赖 MyBatis-Plus，也不依赖 Spring）。
 * 定义 14 个抽象 {@code doXxx} 方法供子类实现，
 * 配合 before/after 钩子实现横切关注点统一处理。
 * 事务等框架关注点不在此层，统一放在桥接层（{@link MpBaseServiceImpl}）。
 * </p>
 * <p>
 * 类层次：
 * <pre>
 *   IBaseService&lt;T&gt; → IService&lt;T&gt; → AbstractBaseService&lt;T&gt; → MpBaseServiceImpl&lt;M, T&gt;
 * </pre>
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>优缺点：零框架依赖使单元测试可直接实例化子类测试钩子与模板流程，无需启动 Spring；
 *       事务统一收口于 {@link MpBaseServiceImpl}，便于统一修改、替换（JTA/Atomikos/Seata）或禁用。</li>
 *   <li>角色：模板方法骨架，定义流程与钩子，不持有任何具体数据访问实现。</li>
 * </ul>
 * </p>
 *
 * @param <T> 实体类型
 * @author 31372
 */
public abstract class AbstractBaseService<T> implements IService<T> {

    // ==================== 新增相关 ====================

    @Override
    public boolean insert(T entity) {
        beforeInsert(entity);
        boolean result = doInsert(entity);
        afterInsert(entity);
        return result;
    }

    @Override
    public boolean saveOrUpdate(T entity) {
        beforeSaveOrUpdate(entity);
        boolean result = doSaveOrUpdate(entity);
        afterSaveOrUpdate(entity);
        return result;
    }

    @Override
    public boolean insertBatch(Collection<T> entityList) {
        beforeInsertBatch(entityList);
        int affected = doInTransaction(() -> doInsertBatch(entityList));
        afterInsertBatch(entityList);
        boolean result = affected > 0 && affected == size(entityList);
        return result;
    }

    @Override
    public boolean saveOrUpdateBatch(Collection<T> entityList) {
        beforeSaveOrUpdateBatch(entityList);
        int affected = doInTransaction(() -> doSaveOrUpdateBatch(entityList));
        afterSaveOrUpdateBatch(entityList);
        boolean result = affected > 0 && affected == size(entityList);
        return result;
    }

    // ==================== 更新相关 ====================

    @Override
    public boolean updateById(T entity) {
        beforeUpdate(entity);
        boolean result = doUpdateById(entity);
        afterUpdate(entity);
        return result;
    }

    @Override
    public boolean updateBatch(Collection<T> entityList) {
        beforeUpdateBatch(entityList);
        int affected = doInTransaction(() -> doUpdateBatch(entityList));
        afterUpdateBatch(entityList);
        boolean result = affected > 0 && affected == size(entityList);
        return result;
    }

    // ==================== 删除相关 ====================

    @Override
    public boolean deleteById(Serializable id) {
        beforeDelete(id);
        boolean result = doDeleteById(id);
        afterDelete(id);
        return result;
    }

    @Override
    public boolean deleteBatch(Collection<? extends Serializable> idList) {
        beforeDeleteBatch(idList);
        int affected = doInTransaction(() -> doDeleteBatch(idList));
        afterDeleteBatch(idList);
        boolean result = affected > 0 && affected == size(idList);
        return result;
    }

    // ==================== 查询相关 ====================

    @Override
    public T getById(Serializable id) {
        return doGetById(id);
    }

    @Override
    public List<T> listByIds(Collection<? extends Serializable> ids) {
        return doListByIds(ids);
    }

    @Override
    public T getOne(IWrapper<T> queryWrapper) {
        return doGetOne(queryWrapper);
    }

    @Override
    public List<T> list(IWrapper<T> queryWrapper) {
        return doList(queryWrapper);
    }

    @Override
    public long count(IWrapper<T> queryWrapper) {
        return doCount(queryWrapper);
    }

    @Override
    public IBasePage<T> page(IBasePage<T> page, IWrapper<T> queryWrapper) {
        return doPage(page, queryWrapper);
    }

    // ==================== 抽象 doXxx 方法（子类必须实现） ====================

    protected abstract boolean doInsert(T entity);

    protected abstract boolean doUpdateById(T entity);

    protected abstract boolean doDeleteById(Serializable id);

    protected abstract T doGetById(Serializable id);

    protected abstract List<T> doListByIds(Collection<? extends Serializable> ids);

    protected abstract T doGetOne(IWrapper<T> queryWrapper);

    protected abstract List<T> doList(IWrapper<T> queryWrapper);

    protected abstract long doCount(IWrapper<T> queryWrapper);

    protected abstract IBasePage<T> doPage(IBasePage<T> page, IWrapper<T> queryWrapper);

    protected abstract boolean doSaveOrUpdate(T entity);

    /**
     * 批量新增，返回实际新增行数
     */
    protected abstract int doInsertBatch(Collection<T> entityList);

    /**
     * 批量更新，返回实际受影响行数
     */
    protected abstract int doUpdateBatch(Collection<T> entityList);

    /**
     * 批量删除，返回实际受影响行数
     */
    protected abstract int doDeleteBatch(Collection<? extends Serializable> idList);

    /**
     * 批量新增或更新，返回实际受影响行数
     */
    protected abstract int doSaveOrUpdateBatch(Collection<T> entityList);

    // ==================== 事务钩子（默认无事务，桥接层可覆写） ====================

    /**
     * 事务执行钩子 — 默认透传，无事务
     * <p>
     * 纯 POJO 声明，不依赖任何框架。子类可覆写以注入具体事务实现
     * （Spring 模板、JTA、Atomikos、Seata 等），或保持默认禁用事务。
     * 事务能力集中在此钩子，便于统一修改、替换、禁用。
     * </p>
     *
     * @param action 事务内执行的业务动作
     * @param <R>    返回值类型
     * @return 动作执行结果
     */
    protected <R> R doInTransaction(Supplier<R> action) {
        return action.get();
    }

    // ==================== 内部工具 ====================

    private static int size(Collection<?> c) {
        return c == null ? 0 : c.size();
    }

    // ==================== 钩子方法（子类可选覆盖） ====================

    /**
     * 新增前钩子，子类可覆盖以实现数据校验、字段填充等
     */
    protected void beforeInsert(T entity) {
    }

    /**
     * 新增后钩子，子类可覆盖以实现事件发布等
     */
    protected void afterInsert(T entity) {
    }

    /**
     * 更新前钩子，子类可覆盖以实现数据校验、字段填充等
     */
    protected void beforeUpdate(T entity) {
    }

    /**
     * 更新后钩子，子类可覆盖以实现事件发布等
     */
    protected void afterUpdate(T entity) {
    }

    /**
     * 批量新增前钩子，子类可覆盖以实现批量数据校验、字段填充等
     */
    protected void beforeInsertBatch(Collection<T> entityList) {
    }

    /**
     * 批量新增后钩子，子类可覆盖以实现事件发布等
     */
    protected void afterInsertBatch(Collection<T> entityList) {
    }

    /**
     * 批量更新前钩子，子类可覆盖以实现批量数据校验、字段填充等
     */
    protected void beforeUpdateBatch(Collection<T> entityList) {
    }

    /**
     * 批量更新后钩子，子类可覆盖以实现事件发布等
     */
    protected void afterUpdateBatch(Collection<T> entityList) {
    }

    /**
     * 删除前钩子，子类可覆盖以实现删除前校验（如关联数据检查）
     */
    protected void beforeDelete(Serializable id) {
    }

    /**
     * 删除后钩子，子类可覆盖以实现事件发布等
     */
    protected void afterDelete(Serializable id) {
    }

    /**
     * 批量删除前钩子，子类可覆盖以实现删除前校验（如关联数据检查）
     */
    protected void beforeDeleteBatch(Collection<? extends Serializable> idList) {
    }

    /**
     * 批量删除后钩子，子类可覆盖以实现事件发布等
     */
    protected void afterDeleteBatch(Collection<? extends Serializable> idList) {
    }

    /**
     * 新增或更新前钩子，子类可覆盖以实现数据校验、字段填充等
     */
    protected void beforeSaveOrUpdate(T entity) {
    }

    /**
     * 新增或更新后钩子，子类可覆盖以实现事件发布等
     */
    protected void afterSaveOrUpdate(T entity) {
    }

    /**
     * 批量新增或更新前钩子，子类可覆盖以实现数据校验、字段填充等
     */
    protected void beforeSaveOrUpdateBatch(Collection<T> entityList) {
    }

    /**
     * 批量新增或更新后钩子，子类可覆盖以实现事件发布等
     */
    protected void afterSaveOrUpdateBatch(Collection<T> entityList) {
    }
}
