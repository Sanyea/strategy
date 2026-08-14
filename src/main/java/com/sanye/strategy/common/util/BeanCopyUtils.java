package com.sanye.strategy.common.util;

import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * Bean 复制工具 — 领域实体 ↔ 持久化对象（PO）转换
 * </p>
 * <p>
 * 基于 Spring {@link BeanUtils#copyProperties} 封装：同名同类型字段自动复制。
 * 用于 Service 桥接层在实体与 PO 之间互转（字段结构一致，仅注解不同）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：转换辅助工具，消除实体↔PO 手写 getter/setter 样板。</li>
 *   <li>优缺点：无状态静态方法，线程安全、零配置；缺点是基于字段名反射复制，
 *       字段重命名时编译期不可察觉，需约定实体与 PO 字段名保持一致。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public final class BeanCopyUtils {

    private BeanCopyUtils() {
    }

    /**
     * 复制源对象到目标类型新实例
     *
     * @param source     源对象
     * @param targetType 目标类型
     * @param <S>        源类型
     * @param <T>        目标类型
     * @return 目标类型新实例，源为 null 返回 null
     */
    public static <S, T> T copy(S source, Class<T> targetType) {
        if (source == null) {
            return null;
        }
        T target = BeanUtils.instantiateClass(targetType);
        BeanUtils.copyProperties(source, target);
        return target;
    }

    /**
     * 复制源对象到目标类型新实例（忽略指定属性）
     * <p>忽略列表典型用于排除不可变字段（主键 id、审计字段 createTime/updateTime、
     * 框架标记 deleted）或业务禁改字段（内置标记 isBuiltIn、角色编码 roleCode 等），
     * 等价于 Spring {@link BeanUtils#copyProperties(Object, Object, String...)}。</p>
     *
     * @param source           源对象
     * @param targetType       目标类型
     * @param ignoreProperties 需忽略复制的属性名（可为空）
     * @param <S>              源类型
     * @param <T>              目标类型
     * @return 目标类型新实例，源为 null 返回 null
     */
    public static <S, T> T copy(S source, Class<T> targetType, String... ignoreProperties) {
        if (source == null) {
            return null;
        }
        T target = BeanUtils.instantiateClass(targetType);
        BeanUtils.copyProperties(source, target, ignoreProperties);
        return target;
    }

    /**
     * 复制源对象属性到目标实例（忽略指定属性，返回目标便于链式调用）
     * <p>原地复制不新建实例：适用于"先查询实体 → 用 DTO 覆盖可变字段"的更新语义，
     * 忽略列表用于排除主键与审计/框架字段。</p>
     *
     * @param source           源对象
     * @param target           目标实例（原地复制，不新建）
     * @param ignoreProperties 需忽略复制的属性名（可为空）
     * @param <S>              源类型
     * @param <T>              目标类型
     * @return 目标实例（与入参 {@code target} 相同）；source 或 target 为 null 返回 target
     */
    public static <S, T> T copy(S source, T target, String... ignoreProperties) {
        if (source == null || target == null) {
            return target;
        }
        BeanUtils.copyProperties(source, target, ignoreProperties);
        return target;
    }

    /**
     * 复制列表到目标类型列表
     *
     * @param sources    源列表
     * @param targetType 目标类型
     * @param <S>        源类型
     * @param <T>        目标类型
     * @return 目标类型列表，源为 null 返回空列表
     */
    public static <S, T> List<T> copyList(List<S> sources, Class<T> targetType) {
        List<T> targets = new ArrayList<>(sources == null ? 0 : sources.size());
        if (sources == null) {
            return targets;
        }
        for (S source : sources) {
            targets.add(copy(source, targetType));
        }
        return targets;
    }
}
