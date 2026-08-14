package com.sanye.strategy.interfaces.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 批量续期入参 DTO — 按绑定行 ID 批量延长 end_time
 * </p>
 * <p>
 * 对应 {@code POST /rbac/user-roles/renew}；续期原地 UPDATE 不新建行，
 * 审计走 {@code ums_oper_log}（门面 {@code RbacManageService.renewBatch}）。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "批量续期入参（按绑定行 ID 延长 end_time）")
public class UserRoleRenewDTO {

    /**
     * 绑定行 ID 列表（{@code ums_user_role.id}，非空）
     */
    @Schema(description = "绑定行 ID 列表（ums_user_role.id，非空）")
    @NotEmpty(message = "bindIds 不能为空")
    private List<Long> bindIds;

    /**
     * 新的结束时间（非空）
     */
    @Schema(description = "新的结束时间（非空）")
    @NotNull(message = "endTime 不能为空")
    private LocalDateTime endTime;
}
