package com.sanye.strategy.interfaces.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * <p>
 * 启停状态入参 DTO — 角色/权限共用
 * </p>
 * <p>
 * {@code status} 为 {@code RoleStatusEnum.code}（0-停用 1-正常）；
 * 非法码值由 Controller 拒绝（400），不落入门面。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "启停状态入参（角色/权限共用）")
public class StatusDTO {

    /**
     * 状态码值 {@code RoleStatusEnum.code}
     */
    @Schema(description = "状态码 0-停用 1-正常（必填）")
    @NotNull(message = "status 不能为空")
    private Integer status;
}
