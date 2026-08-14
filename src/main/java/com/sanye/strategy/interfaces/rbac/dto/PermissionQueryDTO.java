package com.sanye.strategy.interfaces.rbac.dto;

import com.sanye.strategy.domain.enums.PermissionTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * <p>
 * 权限资源查询入参 DTO — 树节点过滤 + 组合分页参数（禁止继承分页对象）
 * </p>
 * <p>
 * 当前用于 {@code /rbac/permissions/tree} 的可选过滤条件；{@code page}/{@code size} 预留组合分页。
 * 过滤命中节点时保留其祖先链，保证返回的树结构完整。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "权限资源查询入参（树过滤）")
public class PermissionQueryDTO {

    /**
     * 资源名称（模糊匹配，可空）
     */
    @Schema(description = "资源名称（模糊匹配，可空）")
    private String permissionName;

    /**
     * 资源类型 {@link PermissionTypeEnum}：目录/菜单/按钮/接口（可空）
     */
    @Schema(description = "资源类型 1-目录 2-菜单 3-按钮 4-接口（可空）")
    private PermissionTypeEnum permissionType;

    /**
     * 权限标识（精确匹配，可空）
     */
    @Schema(description = "权限标识（精确匹配，可空）")
    private String permissionCode;

    /**
     * 页码（从 1 起，默认 1）
     */
    @Schema(description = "页码（从 1 起，默认 1）")
    private Integer page;

    /**
     * 每页行数（默认 10）
     */
    @Schema(description = "每页行数（默认 10）")
    private Integer size;
}
