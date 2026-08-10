package com.sanye.strategy.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 有效状态枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@link com.sanye.strategy.domain.UmsUserAuth#getStatus()} 字段使用，提供类型安全的有效状态定义：
 * <ul>
 *   <li>{@link #INVALID} - 0，失效</li>
 *   <li>{@link #VALID} - 1，有效</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum ValidStatusEnum {

    /**
     * 失效
     */
    INVALID(0, "失效"),

    /**
     * 有效
     */
    VALID(1, "有效");

    /**
     * 状态码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    ValidStatusEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举值，未匹配返回 null
     */
    public static ValidStatusEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (ValidStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
