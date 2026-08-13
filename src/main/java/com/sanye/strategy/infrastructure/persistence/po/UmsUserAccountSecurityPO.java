package com.sanye.strategy.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sanye.strategy.common.base.SimpleBasePO;
import com.sanye.strategy.domain.enums.YesNoEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * <p>
 * 用户账号安全持久化对象（PO）— Mapper 操作对象，ORM 耦合集中于此
 * </p>
 * <p>
 * 对应领域实体 {@link com.sanye.strategy.domain.UmsUserAccountSecurity}，字段一致，差异仅在 MP 映射注解。
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ums_user_account_security")
public class UmsUserAccountSecurityPO extends SimpleBasePO {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 密码错误次数
     */
    private Integer passwordErrorCount;

    /**
     * 账号锁定截止时间
     */
    private LocalDateTime lockTime;

    /**
     * 最后修改密码时间
     */
    private LocalDateTime lastPasswordResetTime;

    /**
     * 是否设置支付密码 {@link YesNoEnum}
     */
    private YesNoEnum hasSetPayPassword;

    /**
     * 支付密码(加密)
     */
    private String payPassword;

    /**
     * 支付密码盐
     */
    private String paySalt;

    /**
     * 是否设置密保问题 {@link YesNoEnum}
     */
    private YesNoEnum secretQuestionStatus;

    /**
     * 双因素认证状态 {@link YesNoEnum}
     */
    private YesNoEnum mfaStatus;

    /**
     * 双因素密钥
     */
    private String mfaSecret;
}
