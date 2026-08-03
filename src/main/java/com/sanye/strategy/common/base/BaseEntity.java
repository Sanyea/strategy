package com.sanye.strategy.common.base;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 核心业务实体基类 - 含审计人信息
 * </p>
 * <p>
 * 适用范围：用户表、订单表、商品表、商户表、配置表等
 * </p>
 * <p>
 * 这类表后台可操作，需要追溯操作人，在 {@link SimpleBaseEntity} 基础上扩展：
 * <ul>
 *   <li>创建人ID create_user_id：插入时自动填充</li>
 *   <li>更新人ID update_user_id：插入/更新时自动填充</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BaseEntity extends SimpleBaseEntity {

    /**
     * 创建人ID
     */
    @TableField(fill = FieldFill.INSERT)
    private Long createUserId;

    /**
     * 更新人ID
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateUserId;
}
