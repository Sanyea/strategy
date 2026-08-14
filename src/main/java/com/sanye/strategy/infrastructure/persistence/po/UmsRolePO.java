package com.sanye.strategy.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sanye.strategy.common.base.BasePO;
import com.sanye.strategy.domain.enums.DataScopeEnum;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.enums.YesNoEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 角色持久化对象（PO）— Mapper 操作对象，ORM 耦合集中于此
 * </p>
 * <p>
 * 对应领域实体 {@link com.sanye.strategy.domain.user.entity.UmsRole}。
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ums_role")
public class UmsRolePO extends BasePO {

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
     * 是否内置角色 {@link YesNoEnum}
     */
    private YesNoEnum isBuiltIn;

    /**
     * 备注
     */
    private String remark;
}
