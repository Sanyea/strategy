package com.sanye.strategy.common.base;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 删除标识枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@link SimpleBaseEntity#getDeleted()} 字段使用，提供类型安全的删除标识定义：
 * <ul>
 *   <li>{@link #NOT_DELETED} - 0，未删除（默认值）</li>
 *   <li>{@link #DELETED} - 1，已删除（逻辑删除）</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum DeleteFlagEnum {

    /**
     * 未删除
     */
    NOT_DELETED(0, "未删除"),

    /**
     * 已删除
     */
    DELETED(1, "已删除");

    /**
     * 状态码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    DeleteFlagEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举值，未匹配返回 null
     */
    public static DeleteFlagEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (DeleteFlagEnum flag : values()) {
            if (flag.code.equals(code)) {
                return flag;
            }
        }
        return null;
    }

    /**
     * 判断是否已删除
     *
     * @return true 表示已删除
     */
    public boolean isDeleted() {
        return this == DELETED;
    }

    /**
     * 判断是否未删除
     *
     * @return true 表示未删除
     */
    public boolean isNotDeleted() {
        return this == NOT_DELETED;
    }
}
