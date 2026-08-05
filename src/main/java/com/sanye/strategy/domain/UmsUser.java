package com.sanye.strategy.domain;

import com.sanye.strategy.common.base.BaseEntity;
import com.sanye.strategy.enums.GenderEnum;
import com.sanye.strategy.enums.IdCardStatusEnum;
import com.sanye.strategy.enums.RegisterChannelEnum;
import com.sanye.strategy.enums.UserStatusEnum;
import com.sanye.strategy.enums.UserTypeEnum;
import com.sanye.strategy.enums.YesNoEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 * 用户主表实体 — 纯 POJO，零框架依赖
 * </p>
 * <p>
 * 持久化映射见 {@link com.sanye.strategy.po.UmsUserPO}。
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UmsUser extends BaseEntity {

    /**
     * 登录账号(唯一)
     */
    private String username;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 真实姓名
     */
    private String realName;

    /**
     * 加密密码(BCrypt/Argon2)
     */
    private String password;

    /**
     * 密码盐(兼容旧体系，新密码算法可空)
     */
    private String salt;

    /**
     * 手机号
     */
    private String phone;

    /**
     * 手机国家码
     */
    private String phoneCountryCode;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 头像URL
     */
    private String avatar;

    /**
     * 性别 {@link GenderEnum}
     */
    private GenderEnum gender;

    /**
     * 出生日期
     */
    private LocalDate birthday;

    /**
     * 身份证号(加密存储)
     */
    private String idCardNo;

    /**
     * 实名认证状态 {@link IdCardStatusEnum}
     */
    private IdCardStatusEnum idCardStatus;

    /**
     * 用户类型 {@link UserTypeEnum}
     */
    private UserTypeEnum userType;

    /**
     * 账号状态 {@link UserStatusEnum}
     */
    private UserStatusEnum userStatus;

    /**
     * 注册渠道 {@link RegisterChannelEnum}
     */
    private RegisterChannelEnum registerChannel;

    /**
     * 注册IP
     */
    private String registerClientIp;

    /**
     * 注册设备ID
     */
    private String registerDeviceId;

    /**
     * 最后登录时间
     */
    private LocalDateTime lastLoginTime;

    /**
     * 最后登录IP
     */
    private String lastLoginIp;

    /**
     * 最后登录设备ID
     */
    private String lastLoginDeviceId;

    /**
     * 用户等级
     */
    private Integer userLevel;

    /**
     * 用户积分
     */
    private Integer userPoint;

    /**
     * 账户余额(建议独立表)
     */
    private BigDecimal userBalance;

    /**
     * 是否VIP {@link YesNoEnum}
     */
    private YesNoEnum isVip;

    /**
     * VIP过期时间
     */
    private LocalDateTime vipExpireTime;

    /**
     * 备注
     */
    private String remark;
}
