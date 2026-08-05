package com.sanye.strategy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 用户类型枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@link com.sanye.strategy.domain.UmsUser#getUserType()} 字段使用，提供类型安全的用户类型定义：
 * <ul>
 *   <li>{@link #NORMAL_USER} - 1，普通用户</li>
 *   <li>{@link #MERCHANT} - 2，商家</li>
 *   <li>{@link #OPERATOR} - 3，运营</li>
 *   <li>{@link #SUPER_ADMIN} - 4，超级管理员</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum UserTypeEnum {

    /**
     * 普通用户
     */
    NORMAL_USER(1, "普通用户"),

    /**
     * 商家
     */
    MERCHANT(2, "商家"),

    /**
     * 运营
     */
    OPERATOR(3, "运营"),

    /**
     * 超级管理员
     */
    SUPER_ADMIN(4, "超级管理员");

    /**
     * 状态码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    UserTypeEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举值，未匹配返回 null
     */
    public static UserTypeEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (UserTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
