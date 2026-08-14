package com.sanye.strategy.interfaces.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * <p>
 * 角色-权限绑定入参 DTO — 覆盖/增量/回收共用的权限 ID 列表
 * </p>
 * <p>
 * PUT 覆盖为空列表表示清空该角色全部权限（合法业务语义）；POST/DELETE 为空列表为无操作。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "角色-权限绑定入参（覆盖/增量/回收共用）")
public class RolePermissionAssignDTO {

    /**
     * 权限资源 ID 列表（可为空——覆盖语义下清空角色权限）
     */
    @Schema(description = "权限资源 ID 列表（可为空——覆盖语义下清空角色权限）")
    @NotNull(message = "permissionIds 不能为 null")
    private List<Long> permissionIds;
}
