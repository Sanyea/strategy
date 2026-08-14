package com.sanye.strategy.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 登入方式枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@code ums_user_login_device.login_type} 字段使用，提供类型安全的登入方式定义：
 * <ul>
 *   <li>{@link #UNKNOWN} - 0，未知</li>
 *   <li>{@link #PHONE} - 1，手机号登录</li>
 *   <li>{@link #SMS_CODE} - 2，验证码登录</li>
 *   <li>{@link #PASSWORD} - 3，账号密码登录</li>
 *   <li>{@link #THIRD_PARTY} - 4，第三方授权登录</li>
 * </ul>
 * 当前仅开放 {@link #PASSWORD}（账号密码流，见 AuthService 登入方式白名单）；手机号/验证码/第三方授权
 * 枚举先行定义，对应登录接口实现后放开白名单即可，无需改库。
 * </p>
 *
 * @author 31372
 */
@Getter
public enum LoginTypeEnum {

    /**
     * 未知
     */
    UNKNOWN(0, "未知"),

    /**
     * 手机号登录
     */
    PHONE(1, "手机号登录"),

    /**
     * 验证码登录
     */
    SMS_CODE(2, "验证码登录"),

    /**
     * 账号密码登录
     */
    PASSWORD(3, "账号密码登录"),

    /**
     * 第三方授权登录
     */
    THIRD_PARTY(4, "第三方授权登录");

    /**
     * 状态码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    LoginTypeEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举值，未匹配返回 null
     */
    public static LoginTypeEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (LoginTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
