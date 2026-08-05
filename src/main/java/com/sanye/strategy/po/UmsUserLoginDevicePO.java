package com.sanye.strategy.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sanye.strategy.common.base.SimpleBasePO;
import com.sanye.strategy.enums.DeviceTypeEnum;
import com.sanye.strategy.enums.YesNoEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * <p>
 * 用户登录设备持久化对象（PO）— Mapper 操作对象，ORM 耦合集中于此
 * </p>
 * <p>
 * 对应领域实体 {@link com.sanye.strategy.domain.UmsUserLoginDevice}，字段一致，差异仅在 MP 映射注解。
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ums_user_login_device")
public class UmsUserLoginDevicePO extends SimpleBasePO {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 设备类型 {@link DeviceTypeEnum}
     */
    private DeviceTypeEnum deviceType;

    /**
     * 操作系统
     */
    private String deviceOs;

    /**
     * 设备品牌
     */
    private String deviceBrand;

    /**
     * 设备型号
     */
    private String deviceModel;

    /**
     * 设备唯一ID
     */
    private String deviceId;

    /**
     * APP版本
     */
    private String appVersion;

    /**
     * 登录IP
     */
    private String loginIp;

    /**
     * 登录时间
     */
    private LocalDateTime loginTime;

    /**
     * Token过期时间
     */
    private LocalDateTime expireTime;

    /**
     * 是否当前有效设备 {@link YesNoEnum}
     */
    private YesNoEnum isCurrent;
}
