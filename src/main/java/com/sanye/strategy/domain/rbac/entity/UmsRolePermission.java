package com.sanye.strategy.domain.rbac.entity;

import lombok.Data;

/**
 * <p>
 * 角色-权限关联实体（物理删除关系表，纯 POJO，零框架依赖）
 * </p>
 * <p>
 * 对应 {@code ums_role_permission} 表。物理删除关系表无 {@code deleted} 列，
 * 不继承 {@code SimpleBaseEntity}/{@code BaseEntity}（约定见 CLAUDE.md「物理删除关系表」）。
 * </p>
 *
 * @author 31372
 */
@Data
public class UmsRolePermission {
    private Long id;         // 主键（雪花）
    private Long roleId;
    private Long permissionId;
    private Long grantUserId; // 授权人ID
    private java.time.LocalDateTime createTime;
    private java.time.LocalDateTime updateTime;
}
