package com.sanye.strategy.domain.user.entity;

import com.sanye.strategy.common.base.SimpleBaseEntity;
import com.sanye.strategy.domain.enums.YesNoEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * <p>
 * 用户账号安全实体 — 纯 POJO，零框架依赖
 * </p>
 * <p>
 * 持久化映射见 {@link com.sanye.strategy.infrastructure.persistence.po.UmsUserAccountSecurityPO}。
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UmsUserAccountSecurity extends SimpleBaseEntity {

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
