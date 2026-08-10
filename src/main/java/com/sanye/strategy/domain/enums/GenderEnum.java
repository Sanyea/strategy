package com.sanye.strategy.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 性别枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@link com.sanye.strategy.domain.UmsUser#getGender()} 字段使用，提供类型安全的性别定义：
 * <ul>
 *   <li>{@link #UNKNOWN} - 0，未知</li>
 *   <li>{@link #MALE} - 1，男</li>
 *   <li>{@link #FEMALE} - 2，女</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum GenderEnum {

    /**
     * 未知
     */
    UNKNOWN(0, "未知"),

    /**
     * 男
     */
    MALE(1, "男"),

    /**
     * 女
     */
    FEMALE(2, "女");

    /**
     * 状态码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    GenderEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举值，未匹配返回 null
     */
    public static GenderEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (GenderEnum gender : values()) {
            if (gender.code.equals(code)) {
                return gender;
            }
        }
        return null;
    }
}
