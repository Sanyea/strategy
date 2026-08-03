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
 * 基础实体类（最顶层）- 所有表必须继承
 * </p>
 * <p>
 * 适用范围：所有表，包括设备表、日志表、安全表、第三方绑定表等
 * </p>
 * <p>
 * 仅包含系统强制、无人工操作也必须有的字段，遵循阿里规范：
 * <ul>
 *   <li>主键 id：BIGINT UNSIGNED，雪花算法生成</li>
 *   <li>逻辑删除 deleted：0-未删除 1-已删除</li>
 *   <li>创建时间 create_time：插入时自动填充</li>
 *   <li>更新时间 update_time：插入/更新时自动填充</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Data
public class SimpleBaseEntity implements Serializable {

    /**
     * 主键ID（雪花算法）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 逻辑删除标识 0-未删除 1-已删除（阿里规范）
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
