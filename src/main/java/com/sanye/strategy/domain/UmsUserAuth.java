package com.sanye.strategy.domain;

import com.sanye.strategy.common.base.SimpleBaseEntity;
import com.sanye.strategy.enums.IdentityTypeEnum;
import com.sanye.strategy.enums.ValidStatusEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 第三方登录关联实体 — 纯 POJO，零框架依赖
 * </p>
 * <p>
 * 持久化映射见 {@link com.sanye.strategy.po.UmsUserAuthPO}。
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UmsUserAuth extends SimpleBaseEntity {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 认证类型 {@link IdentityTypeEnum}
     */
    private IdentityTypeEnum identityType;

    /**
     * 第三方唯一标识(openid/unionid)
     */
    private String identifier;

    /**
     * 凭证(token/密钥)
     */
    private String credential;

    /**
     * 微信unionID
     */
    private String unionId;

    /**
     * 第三方昵称
     */
    private String nickname;

    /**
     * 第三方头像
     */
    private String avatar;

    /**
     * 状态 {@link ValidStatusEnum}
     */
    private ValidStatusEnum status;
}
