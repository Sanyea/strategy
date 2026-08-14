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
 * 角色-权限关联持久化对象（PO）— 物理删除设计，无 deleted 列，不继承 {@code SimpleBasePO}
 * </p>
 * <p>
 * 例外约定见 CLAUDE.md「物理删除关系表」：无 {@code @TableLogic} 字段，不能走
 * {@code MpBaseServiceImpl}（其泛型上限 {@code SimpleBaseEntity} 含 deleted），读写经
 * {@link com.sanye.strategy.domain.rbac.repository.UmsRolePermissionService} 自定义契约收口：
 * 查角色权限码走 XML 联表、写走 {@code BaseMapper.insert/delete}。
 * </p>
 *
 * @author 31372
 */
@Data
@TableName(value = "ums_role_permission")
public class UmsRolePermissionPO {

    /**
     * 主键ID（雪花）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 角色ID
     */
    private Long roleId;

    /**
     * 权限资源ID
     */
    private Long permissionId;

    /**
     * 授权人ID
     */
    private Long grantUserId;

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
