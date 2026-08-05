package com.sanye.strategy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 是否标识枚举（阿里规范）
 * </p>
 * <p>
 * 配套业务标记字段（isVip / hasSetPayPassword / secretQuestionStatus / mfaStatus / isCurrent 等）使用，
 * 提供类型安全的是否标识定义：
 * <ul>
 *   <li>{@link #NO} - 0，否</li>
 *   <li>{@link #YES} - 1，是</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum YesNoEnum {

    /**
     * 否
     */
    NO(0, "否"),

    /**
     * 是
     */
    YES(1, "是");

    /**
     * 状态码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    YesNoEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举值，未匹配返回 null
     */
    public static YesNoEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (YesNoEnum yesNo : values()) {
            if (yesNo.code.equals(code)) {
                return yesNo;
            }
        }
        return null;
    }

    /**
     * 判断是否为是
     *
     * @return true 表示是
     */
    public boolean isYes() {
        return this == YES;
    }

    /**
     * 判断是否为否
     *
     * @return true 表示否
     */
    public boolean isNo() {
        return this == NO;
    }
}
