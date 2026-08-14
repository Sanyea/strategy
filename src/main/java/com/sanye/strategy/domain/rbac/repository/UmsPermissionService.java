package com.sanye.strategy.domain.rbac.repository;

import com.sanye.strategy.common.base.IService;
import com.sanye.strategy.domain.rbac.entity.UmsPermission;

/**
 * <p>
 * 权限资源 Service 接口 — 继承自定义 DIP {@link IService}，操作领域实体 {@link UmsPermission}
 * </p>
 * <p>
 * RBAC 权限资源数据访问契约。功能权限经 {@code ums_role_permission} 关联角色，
 * 鉴权以权限码/接口维度判定（权限扫描注册、注解校验、JWT perms claim 均依赖本契约）。
 * </p>
 *
 * @author 31372
 */
public interface UmsPermissionService extends IService<UmsPermission> {

}
