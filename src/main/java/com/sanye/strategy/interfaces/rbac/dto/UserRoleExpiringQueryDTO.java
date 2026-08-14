package com.sanye.strategy.interfaces.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * <p>
 * 即将/已过期角色绑定查询入参 DTO — 组合分页参数（禁止继承分页对象）
 * </p>
 * <p>
 * {@code days} 为预警时间窗（默认 7）：返回 {@code end_time <= now + days} 的绑定（含已过期）。
 * 底层契约 {@code UmsUserRoleService.listExpiring(now, horizon, offset, limit)} 由本 DTO
 * 换算调用（now=当前时间，horizon=now+days）。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "即将/已过期角色绑定查询入参")
public class UserRoleExpiringQueryDTO {

    /**
     * 到期预警时间窗（天，默认 7）
     */
    @Schema(description = "到期预警时间窗（天，默认 7）")
    private Integer days;

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
