package com.sanye.strategy.interfaces.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * <p>
 * 角色克隆入参 DTO — 指定源角色 ID
 * </p>
 * <p>
 * 克隆语义见门面 {@code RbacManageService.cloneRole}：复制角色字段 + 全套权限绑定，
 * 新角色 role_code = 源 + {@code _COPY_} + 序号（超长截断源段，截断后查重保证唯一）。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "角色克隆入参")
public class RoleCloneDTO {

    /**
     * 源角色 ID（非空）
     */
    @Schema(description = "源角色 ID（必填）")
    @NotNull(message = "sourceRoleId 不能为空")
    private Long sourceRoleId;
}
