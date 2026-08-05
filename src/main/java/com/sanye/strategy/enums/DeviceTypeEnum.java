package com.sanye.strategy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 设备类型枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@link com.sanye.strategy.domain.UmsUserLoginDevice#getDeviceType()} 字段使用，提供类型安全的设备类型定义：
 * <ul>
 *   <li>{@link #PHONE} - 1，手机</li>
 *   <li>{@link #PAD} - 2，平板</li>
 *   <li>{@link #PC} - 3，PC</li>
 *   <li>{@link #MINI_PROGRAM} - 4，小程序</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum DeviceTypeEnum {

    /**
     * 手机
     */
    PHONE(1, "手机"),

    /**
     * 平板
     */
    PAD(2, "平板"),

    /**
     * PC
     */
    PC(3, "PC"),

    /**
     * 小程序
     */
    MINI_PROGRAM(4, "小程序");

    /**
     * 设备类型码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    DeviceTypeEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 设备类型码
     * @return 对应枚举值，未匹配返回 null
     */
    public static DeviceTypeEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (DeviceTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
