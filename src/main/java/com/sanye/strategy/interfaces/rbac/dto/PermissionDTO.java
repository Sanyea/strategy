package com.sanye.strategy.interfaces.rbac.dto;

import com.sanye.strategy.domain.enums.PermissionTypeEnum;
import com.sanye.strategy.domain.enums.YesNoEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * <p>
 * 权限资源新增/修改入参 DTO
 * </p>
 * <p>
 * 携带目录/菜单/按钮/接口的可变字段；{@code permissionCode} 新建后禁改（改码=换新资源，
 * 防角色绑定引用断裂）、内置资源禁改均由门面守卫拦截。状态与内置标记由门面固定
 * （新增默认 NORMAL/NOT_BUILT_IN）。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "权限资源新增/修改入参")
public class PermissionDTO {

    /**
     * 父资源ID，0-根
     */
    @Schema(description = "父资源 ID，0-根")
    private Long parentId;

    /**
     * 资源名称
     */
    private String permissionName;

    /**
     * 前端标题，菜单/目录展示用，可空
     */
    @Schema(description = "前端标题")
    private String title;

    /**
     * 资源类型 {@link PermissionTypeEnum}：目录/菜单/按钮/接口
     */
    @Schema(description = "资源类型 1-目录 2-菜单 3-按钮 4-接口")
    private PermissionTypeEnum permissionType;

    /**
     * 权限标识，如 system:user:create（按钮/接口用，可空）
     */
    @Schema(description = "权限标识，如 system:user:create（按钮/接口用，可空）")
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
    @Schema(description = "接口请求方法 GET/POST/PUT/DELETE/PATCH")
    private String apiMethod;

    /**
     * 接口路径，如 /api/system/user
     */
    @Schema(description = "接口路径，如 /api/system/user")
    private String apiPath;

    /**
     * 图标
     */
    private String icon;

    /**
     * 显示顺序
     */
    @Schema(description = "显示顺序")
    private Integer sortOrder;

    /**
     * 是否显示 {@link YesNoEnum}，按钮/接口忽略
     */
    @Schema(description = "是否显示（按钮/接口忽略）")
    private YesNoEnum isVisible;

    /**
     * 是否需要权限控制 {@link YesNoEnum}，前端据此判断是否展示权限控制
     */
    @Schema(description = "是否需要权限控制 0-否 1-是")
    private YesNoEnum requiresAuth;

    /**
     * 备注
     */
    private String remark;
}
