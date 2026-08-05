package com.sanye.strategy.common.base;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 领域实体基类（最顶层）— 纯 POJO，零框架依赖
 * </p>
 * <p>
 * 半充血模型：字段 + 轻量业务行为，不承载任何持久化细节。
 * 持久化映射（主键策略、逻辑删除、自动填充）统一下沉到 PO 层
 * （{@link SimpleBasePO}），实体与 ORM 完全解耦，换 ORM 只动 PO。
 * </p>
 * <p>
 * 适用范围：所有领域实体继承。
 * 仅包含系统强制、无人工操作也必须有的字段：
 * <ul>
 *   <li>主键 id：雪花算法生成（生成策略在 PO 层声明）</li>
 *   <li>逻辑删除 deleted：0-未删除 1-已删除</li>
 *   <li>创建时间 create_time</li>
 *   <li>更新时间 update_time</li>
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
    private Long id;

    /**
     * 逻辑删除标识 0-未删除 1-已删除（阿里规范）
     */
    private DeleteFlagEnum deleted;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    private static final long serialVersionUID = 1L;
}
