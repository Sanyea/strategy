package com.sanye.strategy.common.base;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 持久化对象（PO）基类 — 与 ORM 映射相关，框架耦合集中于此
 * </p>
 * <p>
 * 对应领域实体 {@link SimpleBaseEntity}，字段一致，差异仅在映射注解：
 * <ul>
 *   <li>{@code @TableId(ASSIGN_ID)} 雪花主键生成</li>
 *   <li>{@code @TableLogic} 逻辑删除（SQL 自动追加 deleted=0，删除变更新）</li>
 *   <li>{@code @TableField(fill)} 自动填充（配合 MetaObjectHandler）</li>
 * </ul>
 * </p>
 * <p>
 * 业务代码不直接操作 PO，由 {@link MpBaseServiceImpl} 桥接层完成实体↔PO 转换。
 * </p>
 *
 * @author 31372
 */
@Data
public class SimpleBasePO implements Serializable {

    /**
     * 主键ID（雪花算法，MP 自动生成）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 逻辑删除标识 0-未删除 1-已删除
     */
    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private DeleteFlagEnum deleted;

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

    private static final long serialVersionUID = 1L;
}
