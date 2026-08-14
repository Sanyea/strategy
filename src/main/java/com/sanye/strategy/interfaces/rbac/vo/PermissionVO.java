package com.sanye.strategy.interfaces.rbac.vo;

import com.sanye.strategy.domain.enums.PermissionTypeEnum;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.enums.YesNoEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 权限资源视图 VO — 树节点载体（含子节点列表）
 * </p>
 * <p>
 * 用于权限资源树、菜单树与角色当前权限集展示；{@code children} 为子节点递归结构，
 * 叶子节点为空列表。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "权限资源视图（树节点）")
public class PermissionVO {

    /**
     * 资源ID
     */
    private Long id;

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
     * 权限标识，如 system:user:create
     */
    @Schema(description = "权限标识，如 system:user:create")
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
     * 接口路径
     */
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
     * 资源状态 {@link RoleStatusEnum}：0-停用 1-正常
     */
    @Schema(description = "资源状态 0-停用 1-正常")
    private RoleStatusEnum status;

    /**
     * 是否内置资源 {@link YesNoEnum}
     */
    @Schema(description = "是否内置资源")
    private YesNoEnum isBuiltIn;

    /**
     * 是否需要权限控制 {@link YesNoEnum}，前端据此判断是否展示权限控制
     */
    @Schema(description = "是否需要权限控制 0-否 1-是")
    private YesNoEnum requiresAuth;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 子节点列表（树结构）
     */
    @Schema(description = "子节点列表（树结构）")
    private List<PermissionVO> children = new ArrayList<>();
}
