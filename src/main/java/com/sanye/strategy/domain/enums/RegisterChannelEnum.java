package com.sanye.strategy.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 注册渠道枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@link com.sanye.strategy.domain.UmsUser#getRegisterChannel()} 字段使用，提供类型安全的注册渠道定义：
 * <ul>
 *   <li>{@link #UNKNOWN} - 0，未知</li>
 *   <li>{@link #APP} - 1，APP</li>
 *   <li>{@link #MINI_PROGRAM} - 2，小程序</li>
 *   <li>{@link #H5} - 3，H5</li>
 *   <li>{@link #PC} - 4，PC</li>
 *   <li>{@link #THIRD_PARTY} - 5，第三方授权</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum RegisterChannelEnum {

    /**
     * 未知
     */
    UNKNOWN(0, "未知"),

    /**
     * APP
     */
    APP(1, "APP"),

    /**
     * 小程序
     */
    MINI_PROGRAM(2, "小程序"),

    /**
     * H5
     */
    H5(3, "H5"),

    /**
     * PC
     */
    PC(4, "PC"),

    /**
     * 第三方授权
     */
    THIRD_PARTY(5, "第三方授权");

    /**
     * 状态码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    RegisterChannelEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举值，未匹配返回 null
     */
    public static RegisterChannelEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (RegisterChannelEnum channel : values()) {
            if (channel.code.equals(code)) {
                return channel;
            }
        }
        return null;
    }
}
