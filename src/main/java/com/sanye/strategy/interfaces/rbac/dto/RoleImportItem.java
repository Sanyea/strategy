package com.sanye.strategy.interfaces.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * <p>
 * 角色导入条目 DTO — 按 role_code 匹配的角色导入项
 * </p>
 * <p>
 * 与 JSON 反序列化（Jackson 3 {@code tools.jackson.databind.ObjectMapper}）配合：
 * 已存在跳过 / overwrite 覆盖权限绑定；未注册权限码忽略该项并告警（不整体失败）。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "角色导入条目")
public class RoleImportItem {

    /**
     * 角色编码（匹配键）
     */
    @Schema(description = "角色编码（匹配键）")
    private String roleCode;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 显示顺序
     */
    private Integer sortOrder;

    /**
     * 备注
     */
    private String remark;

    /**
     * 需绑定的权限码列表（未注册码忽略告警）
     */
    @Schema(description = "需绑定的权限码列表（未注册码忽略告警）")
    private List<String> permissionCodes;
}
