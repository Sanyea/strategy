package com.sanye.strategy.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 收入水平枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@link com.sanye.strategy.domain.UmsUserProfile#getIncomeLevel()} 字段使用，提供类型安全的收入水平定义：
 * <ul>
 *   <li>{@link #UNKNOWN} - 0，未知</li>
 *   <li>{@link #L1_UNDER_3K} - 1，3k以下</li>
 *   <li>{@link #L2_3K_TO_8K} - 2，3k-8k</li>
 *   <li>{@link #L3_8K_TO_15K} - 3，8k-15k</li>
 *   <li>{@link #L4_15K_TO_30K} - 4，15k-30k</li>
 *   <li>{@link #L5_OVER_30K} - 5，30k以上</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum IncomeLevelEnum {

    /**
     * 未知
     */
    UNKNOWN(0, "未知"),

    /**
     * 3k以下
     */
    L1_UNDER_3K(1, "3k以下"),

    /**
     * 3k-8k
     */
    L2_3K_TO_8K(2, "3k-8k"),

    /**
     * 8k-15k
     */
    L3_8K_TO_15K(3, "8k-15k"),

    /**
     * 15k-30k
     */
    L4_15K_TO_30K(4, "15k-30k"),

    /**
     * 30k以上
     */
    L5_OVER_30K(5, "30k以上");

    /**
     * 状态码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    IncomeLevelEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举值，未匹配返回 null
     */
    public static IncomeLevelEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (IncomeLevelEnum level : values()) {
            if (level.code.equals(code)) {
                return level;
            }
        }
        return null;
    }
}
