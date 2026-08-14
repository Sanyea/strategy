package com.sanye.strategy.domain.user.repository;

import com.sanye.strategy.common.base.IService;
import com.sanye.strategy.domain.user.entity.UmsRole;

/**
 * <p>
 * 角色 Service 接口 — 继承自定义 DIP {@link IService}，操作领域实体 {@link UmsRole}
 * </p>
 * <p>
 * RBAC 角色数据访问契约。用户类型已迁移为角色：注册默认绑定 {@code NORMAL_USER}，
 * 认证签发时经 {@link UmsUserRoleService#getRoleCodesByUserId} 组装角色码 claim。
 * </p>
 *
 * @author 31372
 */
public interface UmsRoleService extends IService<UmsRole> {

}
