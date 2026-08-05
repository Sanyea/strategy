package com.sanye.strategy.common.base;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 核心业务领域实体基类 - 含审计人信息
 * </p>
 * <p>
 * 纯 POJO，零框架依赖。持久化映射见 {@link BasePO}。
 * 在 {@link SimpleBaseEntity} 基础上扩展审计人字段：
 * <ul>
 *   <li>创建人ID create_user_id</li>
 *   <li>更新人ID update_user_id</li>
 * </ul>
 * </p>
 * <p>
 * 适用范围：用户表、订单表、商品表、商户表、配置表等
 * （后台可操作、需追溯操作人的业务表）。
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
    private Long createUserId;

    /**
     * 更新人ID
     */
    private Long updateUserId;
}
