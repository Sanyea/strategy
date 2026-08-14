package com.sanye.strategy.interfaces.rbac.vo;

import com.sanye.strategy.domain.enums.DataScopeEnum;
import com.sanye.strategy.domain.enums.RoleStatusEnum;
import com.sanye.strategy.domain.enums.YesNoEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 角色视图 VO — 分页列表与详情共用的角色展示载体
 * </p>
 * <p>
 * {@code permissionCodes} 仅详情端点（{@code GET /rbac/roles/{id}}）填充，分页列表为 null。
 * 数据权限范围与状态均以枚举形态输出（与 {@code DataScopeEnum}/{@code RoleStatusEnum} 一致）。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "角色视图（分页/详情共用）")
public class RoleVO {

    /**
     * 角色ID
     */
    private Long id;

    /**
     * 角色编码
     */
    private String roleCode;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 数据权限范围 {@link DataScopeEnum}
     */
    @Schema(description = "数据权限范围 1-全部 2-仅本人 3-本部门 4-本部门及以下 5-自定义")
    private DataScopeEnum dataScope;

    /**
     * 显示顺序
     */
    @Schema(description = "显示顺序")
    private Integer sortOrder;

    /**
     * 角色状态 {@link RoleStatusEnum}
     */
    @Schema(description = "角色状态 0-停用 1-正常")
    private RoleStatusEnum status;

    /**
     * 是否内置角色 {@link YesNoEnum}
     */
    @Schema(description = "是否内置角色")
    private YesNoEnum isBuiltIn;

    /**
     * 备注
     */
    private String remark;

    /**
     * 角色当前生效权限码列表（仅详情端点填充，分页为 null）
     */
    @Schema(description = "当前生效权限码列表（仅详情端点填充，分页为 null）")
    private List<String> permissionCodes;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
