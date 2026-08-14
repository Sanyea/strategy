package com.sanye.strategy.interfaces.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 批量授角色入参 DTO — 多用户授同一角色（带生效时段）
 * </p>
 * <p>
 * {@code begin} 与 {@code end} 的先后校验（begin &lt; end）由门面
 * {@code RbacManageService.assignRolesBatch} 承担，本 DTO 仅约束必填项。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "批量授角色入参（多用户授同一角色）")
public class UserRoleBatchAssignDTO {

    /**
     * 目标用户 ID 列表（非空）
     */
    @Schema(description = "目标用户 ID 列表（非空）")
    @NotEmpty(message = "userIds 不能为空")
    private List<Long> userIds;

    /**
     * 角色 ID
     */
    @Schema(description = "角色 ID")
    @NotNull(message = "roleId 不能为空")
    private Long roleId;

    /**
     * 角色生效开始时间（NULL=不限制）
     */
    @Schema(description = "角色生效开始时间（NULL=不限制）")
    private LocalDateTime beginTime;

    /**
     * 角色生效结束时间（NULL=不限制）
     */
    @Schema(description = "角色生效结束时间（NULL=不限制）")
    private LocalDateTime endTime;
}
