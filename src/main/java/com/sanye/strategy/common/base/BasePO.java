package com.sanye.strategy.common.base;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 核心业务持久化对象（PO）基类 - 含审计人字段
 * </p>
 * <p>
 * 对应领域实体 {@link BaseEntity}。createUserId/updateUserId 标记自动填充，
 * 由 MetaObjectHandler（见 {@code common.config.MybatisPlusConfig}）在执行插入/更新时填充。
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BasePO extends SimpleBasePO {

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
