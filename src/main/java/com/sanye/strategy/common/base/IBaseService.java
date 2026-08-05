package com.sanye.strategy.common.base;


import com.sanye.strategy.common.model.IBasePage;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
/**
 * @author 31372
 */
public interface IBaseService<T> {
    // 新增
    boolean insert(T entity);
    // 根据ID更新
    boolean updateById(T entity);
    // 根据ID逻辑删除
    boolean deleteById(Serializable id);
    // 根据ID查询
    T getById(Serializable id);
    // 根据ID批量查询
    List<T> listByIds(Collection<? extends Serializable> ids);
    // 条件查询单条
    T getOne(IWrapper<T> queryWrapper);
    // 条件查询列表
    List<T> list(IWrapper<T> queryWrapper);
    // 条件统计
    long count(IWrapper<T> queryWrapper);
    // 分页查询
    IBasePage<T> page(IBasePage<T> page, IWrapper<T> queryWrapper);
    // 保存或更新（有ID则更新，无则新增）
    boolean saveOrUpdate(T entity);
}