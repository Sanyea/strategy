package com.sanye.strategy.interfaces.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * <p>
 * 角色新增/修改入参 DTO
 * </p>
 * <p>
 * 仅携带角色可变字段（roleCode/roleName/dataScope/sortOrder/remark）；状态与内置标记
 * 由门面按语义固定（新增默认 NORMAL/NOT_BUILT_IN），不随入参篡改。
 * {@code dataScope} 为 1-全部 2-仅本人 3-本部门 4-本部门及以下 5-自定义（见
 * {@code DataScopeEnum}），前端传码值。{@code roleCode} 新增与修改均必填（内置角色禁改
 * 由门面守卫，修改时也须携带原码比对）。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "角色新增/修改入参")
public class RoleDTO {

    /**
     * 角色编码，如 SUPER_ADMIN（必填）
     */
    @Schema(description = "角色编码（必填），如 SUPER_ADMIN")
    @NotBlank(message = "roleCode 不能为空")
    private String roleCode;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 数据权限范围码（{@code DataScopeEnum.code}）
     */
    @Schema(description = "数据权限范围 1-全部 2-仅本人 3-本部门 4-本部门及以下 5-自定义")
    private Integer dataScope;

    /**
     * 显示顺序
     */
    @Schema(description = "显示顺序")
    private Integer sortOrder;

    /**
     * 备注
     */
    private String remark;
}
