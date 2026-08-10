package com.sanye.strategy.interfaces.common;

import com.sanye.strategy.common.base.IBaseService;
import com.sanye.strategy.common.base.IWrapper;
import com.sanye.strategy.common.exception.BizException;
import com.sanye.strategy.common.model.IBasePage;
import com.sanye.strategy.common.response.R;
import com.sanye.strategy.common.response.ResultCode;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * <p>
 * Controller 抽象基类 — 标准化 GET 查询端点
 * </p>
 * <p>
 * 提供 {@code GET /{id}} 和 {@code GET /page} 两个标准查询端点。
 * 新增（POST）、更新（PUT）、删除（DELETE）因含业务逻辑，由子类自行实现。
 * </p>
 * <p>
 * 泛型说明：
 * <ul>
 *   <li>{@code T} — 实体类型（Domain 类）</li>
 *   <li>{@code S} — Service 接口，必须继承 {@link IBaseService}</li>
 *   <li>{@code Q} — 请求 DTO，无父类约束，分页参数由组合方式提供</li>
 *   <li>{@code V} — 响应 VO，无父类约束</li>
 * </ul>
 * </p>
 *
 * @param <T> 实体类型
 * @param <S> Service 类型，必须继承 {@link IBaseService}{@code <T>}
 * @param <Q> 查询请求 DTO
 * @param <V> 响应 VO
 * @author 31372
 */
public abstract class BaseController<T, S extends IBaseService<T>, Q, V> {

    @Autowired
    protected S service;

    // ==================== 子类必须实现的抽象方法 ====================

    /**
     * 实体 → VO 转换
     *
     * @param entity 领域实体
     * @return 视图对象
     */
    protected abstract V toVO(T entity);

    /**
     * 从请求 DTO 构建查询条件
     *
     * @param query 查询请求 DTO
     * @return 查询条件包装器
     */
    protected abstract IWrapper<T> buildQuery(Q query);

    /**
     * 从请求 DTO 提取分页参数
     *
     * @param query 查询请求 DTO
     * @return 分页对象
     */
    protected abstract IBasePage<T> buildPage(Q query);

    // ==================== 标准化查询端点 ====================

    /**
     * 根据 ID 查询单条记录
     *
     * @param id 主键 ID
     * @return 统一响应
     */
    @GetMapping("/{id}")
    public R<V> getById(@PathVariable Long id) {
        T entity = service.getById(id);
        if (entity == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        return R.ok(toVO(entity));
    }

    /**
     * 分页条件查询
     *
     * @param query 查询请求 DTO
     * @return 统一响应，data 为分页 VO
     */
    @GetMapping("/page")
    public R<IBasePage<V>> page(@Valid Q query) {
        IBasePage<T> pageResult = service.page(buildPage(query), buildQuery(query));
        return R.ok(pageResult.convert(this::toVO));
    }
}
