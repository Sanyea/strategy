package com.sanye.strategy.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sanye.strategy.common.base.BasePO;
import com.sanye.strategy.domain.enums.PermissionTypeEnum;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.enums.YesNoEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 权限资源持久化对象（PO）— Mapper 操作对象，ORM 耦合集中于此
 * </p>
 * <p>
 * 对应领域实体 {@link com.sanye.strategy.domain.rbac.entity.UmsPermission}。
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ums_permission")
public class UmsPermissionPO extends BasePO {

    /**
     * 父资源ID，0-根
     */
    private Long parentId;

    /**
     * 资源名称
     */
    private String permissionName;

    /**
     * 前端标题，菜单/目录展示用，可空
     */
    private String title;

    /**
     * 资源类型 {@link PermissionTypeEnum}：目录/菜单/按钮/接口
     */
    private PermissionTypeEnum permissionType;

    /**
     * 权限标识，如 system:user:create（按钮/接口用）
     */
    private String permissionCode;

    /**
     * 前端路由地址
     */
    private String routePath;

    /**
     * 前端组件路径
     */
    private String componentPath;

    /**
     * 接口请求方法 GET/POST/PUT/DELETE/PATCH
     */
    private String apiMethod;

    /**
     * 接口路径，如 /api/system/user
     */
    private String apiPath;

    /**
     * 图标
     */
    private String icon;

    /**
     * 显示顺序
     */
    private Integer sortOrder;

    /**
     * 是否外链 {@link YesNoEnum}
     */
    private YesNoEnum isFrame;

    /**
     * 是否缓存 {@link YesNoEnum}
     */
    private YesNoEnum isCache;

    /**
     * 是否显示 {@link YesNoEnum}，按钮/接口忽略
     */
    private YesNoEnum isVisible;

    /**
     * 资源状态 {@link RoleStatusEnum}：0-停用 1-正常
     */
    private RoleStatusEnum status;

    /**
     * 是否内置资源 {@link YesNoEnum}
     */
    private YesNoEnum isBuiltIn;

    /**
     * 是否需要权限控制 {@link YesNoEnum}，供前端判断是否展示权限控制
     */
    private YesNoEnum requiresAuth;

    /**
     * 备注
     */
    private String remark;
}
