package com.sanye.strategy.interfaces.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <p>
 * 单角色续期入参 DTO — 仅携带新的结束时间
 * </p>
 * <p>
 * 用户/角色由路径 {@code /rbac/users/{id}/roles/{roleId}/renew} 承载；
 * 对应门面 {@code RbacManageService.renewUserRole}。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "单角色续期入参")
public class UserRoleRenewSingleDTO {

    /**
     * 新的结束时间（非空）
     */
    @Schema(description = "新的结束时间（非空）")
    @NotNull(message = "endTime 不能为空")
    private LocalDateTime endTime;
}
