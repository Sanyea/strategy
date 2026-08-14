package com.sanye.strategy.interfaces.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <p>
 * 用户-角色绑定入参 DTO — 角色覆盖/批量授权条目
 * </p>
 * <p>
 * 角色覆盖（{@code replaceUserRoles}）时每行一个角色；begin/end 可空（空=不限时段）。
 * 仅需 roleId/beginTime/endTime，userId 由门面统一取参（覆盖调用方传单 userId）。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "用户-角色绑定入参（角色覆盖/批量授权条目）")
public class UserRoleAssignDTO {

    /**
     * 角色ID
     */
    @Schema(description = "角色 ID")
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
