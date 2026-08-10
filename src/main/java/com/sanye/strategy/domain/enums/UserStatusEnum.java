package com.sanye.strategy.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 账号状态枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@link com.sanye.strategy.domain.UmsUser#getUserStatus()} 字段使用，提供类型安全的账号状态定义：
 * <ul>
 *   <li>{@link #CANCELLED} - 0，注销</li>
 *   <li>{@link #NORMAL} - 1，正常</li>
 *   <li>{@link #FROZEN} - 2，冻结</li>
 *   <li>{@link #MUTED} - 3，禁言</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum UserStatusEnum {

    /**
     * 注销
     */
    CANCELLED(0, "注销"),

    /**
     * 正常
     */
    NORMAL(1, "正常"),

    /**
     * 冻结
     */
    FROZEN(2, "冻结"),

    /**
     * 禁言
     */
    MUTED(3, "禁言");

    /**
     * 状态码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    UserStatusEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举值，未匹配返回 null
     */
    public static UserStatusEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (UserStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
