package com.sanye.strategy.interfaces.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * <p>
 * 角色分页查询入参 DTO — 组合分页参数（禁止继承分页对象）
 * </p>
 * <p>
 * {@code status} 传码值（{@code RoleStatusEnum.code}，0-停用 1-正常），{@code page}/{@code size} 为分页参数，
 * {@code sortField}/{@code sortOrder} 为排序字段/方向（排序字段须在 Controller 白名单内，防 SQL 注入）。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "角色分页查询入参")
public class RoleQueryDTO {

    /**
     * 角色编码（精确匹配，可空）
     */
    @Schema(description = "角色编码（精确匹配，可空）")
    private String roleCode;

    /**
     * 角色名称（模糊匹配，可空）
     */
    @Schema(description = "角色名称（模糊匹配，可空）")
    private String roleName;

    /**
     * 状态码值 {@code RoleStatusEnum.code}（0-停用 1-正常，可空）
     */
    @Schema(description = "角色状态 0-停用 1-正常（可空）")
    private Integer status;

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

    /**
     * 排序字段（白名单：role_code/role_name/sort_order/create_time）
     */
    @Schema(description = "排序字段（白名单：role_code/role_name/sort_order/create_time）")
    private String sortField;

    /**
     * 排序方向 asc/desc（默认 asc）
     */
    @Schema(description = "排序方向 asc/desc（默认 asc）")
    private String sortOrder;
}
