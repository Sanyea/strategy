package com.sanye.strategy.domain.user.entity;

import com.sanye.strategy.common.base.BaseEntity;
import com.sanye.strategy.domain.enums.DataScopeEnum;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.enums.YesNoEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 角色实体 — RBAC 角色（纯 POJO，零框架依赖）
 * </p>
 * <p>
 * 用户类型（原 {@code ums_user.user_type} 列）已迁移为 RBAC 角色：权限归属收口到
 * {@code ums_user_role} 关联，accessToken 以角色码数组 claim（{@code roles}）承载，
 * 授权判定不再依赖单列 user_type。内置角色（{@code isBuiltIn=1}）不允许删除。
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UmsRole extends BaseEntity {

    /**
     * 角色编码，如 SUPER_ADMIN
     */
    private String roleCode;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 数据权限范围 {@link DataScopeEnum}
     */
    private DataScopeEnum dataScope;

    /**
     * 显示顺序
     */
    private Integer sortOrder;

    /**
     * 角色状态 {@link RoleStatusEnum}
     */
    private RoleStatusEnum status;

    /**
     * 是否内置角色 {@link YesNoEnum}，内置角色不允许删除
     */
    private YesNoEnum isBuiltIn;

    /**
     * 备注
     */
    private String remark;
}
