package com.sanye.strategy.domain.user.entity;

import lombok.Data;

/**
 * <p>
 * 用户-角色关联实体（物理删除关系表，纯 POJO，零框架依赖）
 * </p>
 * <p>
 * 对应 {@code ums_user_role} 表。物理删除关系表无 {@code deleted} 列，
 * 不继承 {@code SimpleBaseEntity}/{@code BaseEntity}（约定见 CLAUDE.md「物理删除关系表」）。
 * </p>
 *
 * @author 31372
 */
@Data
public class UmsUserRole {
    /** 主键ID（雪花） */
    private Long id;
    /** 用户ID */
    private Long userId;
    /** 角色ID */
    private Long roleId;
    /** 角色生效开始时间（NULL=不限制） */
    private java.time.LocalDateTime beginTime;
    /** 角色生效结束时间（NULL=不限制；续费=UPDATE 本字段原地延长，审计走 ums_oper_log） */
    private java.time.LocalDateTime endTime;
    /** 授权人ID */
    private Long assignerId;
    /** 创建时间 */
    private java.time.LocalDateTime createTime;
    /** 更新时间 */
    private java.time.LocalDateTime updateTime;
}
