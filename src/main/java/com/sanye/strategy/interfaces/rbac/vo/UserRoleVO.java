package com.sanye.strategy.interfaces.rbac.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <p>
 * 用户-角色绑定视图 VO — 用户角色列表 / 即将到期绑定共用
 * </p>
 * <p>
 * 由 {@code UmsUserRole} 绑定行 + 角色基本信息（roleCode/roleName）装配；
 * 角色已删除/缺失时 roleCode/roleName 为 null，不阻断列表展示。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "用户-角色绑定视图")
public class UserRoleVO {

    /**
     * 绑定行ID（{@code ums_user_role.id}，批量续期/解绑引用）
     */
    @Schema(description = "绑定行 ID（ums_user_role.id，批量续期/解绑引用）")
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 角色ID
     */
    private Long roleId;

    /**
     * 角色编码（装配，缺失为 null）
     */
    @Schema(description = "角色编码（装配，缺失为 null）")
    private String roleCode;

    /**
     * 角色名称（装配，缺失为 null）
     */
    @Schema(description = "角色名称（装配，缺失为 null）")
    private String roleName;

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

    /**
     * 授权人ID
     */
    @Schema(description = "授权人 ID")
    private Long assignerId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
