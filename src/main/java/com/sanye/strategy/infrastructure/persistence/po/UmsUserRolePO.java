package com.sanye.strategy.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <p>
 * 用户-角色关联持久化对象（PO）— 物理删除设计，无 deleted 列，不继承 {@code SimpleBasePO}
 * </p>
 * <p>
 * 例外约定见 CLAUDE.md「物理删除关系表」：无 {@code @TableLogic} 字段，不能走
 * {@code MpBaseServiceImpl}（其泛型上限 {@code SimpleBaseEntity} 含 deleted），读写经
 * {@link com.sanye.strategy.domain.user.repository.UmsUserRoleService} 自定义契约收口：
 * 查角色码走 XML 联表、写走 {@code BaseMapper.insert/updateById}。
 * </p>
 *
 * @author 31372
 */
@Data
@TableName(value = "ums_user_role")
public class UmsUserRolePO {

    /**
     * 主键ID（雪花）
     */
    @TableId(type = IdType.ASSIGN_ID)
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
     * 角色生效开始时间（NULL=不限制）
     */
    private LocalDateTime beginTime;

    /**
     * 角色生效结束时间（NULL=不限制；续费=UPDATE 本字段原地延长，审计走 ums_oper_log）
     */
    private LocalDateTime endTime;

    /**
     * 授权人ID
     */
    private Long assignerId;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
