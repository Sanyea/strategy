package com.sanye.strategy.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sanye.strategy.common.base.SimpleBasePO;
import com.sanye.strategy.domain.enums.IdentityTypeEnum;
import com.sanye.strategy.domain.enums.ValidStatusEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 第三方登录关联持久化对象（PO）— Mapper 操作对象，ORM 耦合集中于此
 * </p>
 * <p>
 * 对应领域实体 {@link com.sanye.strategy.domain.UmsUserAuth}，字段一致，差异仅在 MP 映射注解。
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ums_user_auth")
public class UmsUserAuthPO extends SimpleBasePO {

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
