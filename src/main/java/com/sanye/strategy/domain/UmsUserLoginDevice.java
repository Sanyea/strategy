package com.sanye.strategy.domain;

import com.sanye.strategy.common.base.SimpleBaseEntity;
import com.sanye.strategy.enums.DeviceTypeEnum;
import com.sanye.strategy.enums.YesNoEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * <p>
 * 用户登录设备实体 — 纯 POJO，零框架依赖
 * </p>
 * <p>
 * 持久化映射见 {@link com.sanye.strategy.po.UmsUserLoginDevicePO}。
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UmsUserLoginDevice extends SimpleBaseEntity {

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

    /**
     * 刷新令牌 SHA-256 哈希（Hex，非明文）
     */
    private String refreshTokenHash;
}
