package com.sanye.strategy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 实名认证状态枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@link com.sanye.strategy.domain.UmsUser#getIdCardStatus()} 字段使用，提供类型安全的实名认证状态定义：
 * <ul>
 *   <li>{@link #UNVERIFIED} - 0，未认证</li>
 *   <li>{@link #VERIFYING} - 1，认证中</li>
 *   <li>{@link #VERIFIED} - 2，已认证</li>
 *   <li>{@link #VERIFY_FAILED} - 3，认证失败</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum IdCardStatusEnum {

    /**
     * 未认证
     */
    UNVERIFIED(0, "未认证"),

    /**
     * 认证中
     */
    VERIFYING(1, "认证中"),

    /**
     * 已认证
     */
    VERIFIED(2, "已认证"),

    /**
     * 认证失败
     */
    VERIFY_FAILED(3, "认证失败");

    /**
     * 状态码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    IdCardStatusEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举值，未匹配返回 null
     */
    public static IdCardStatusEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (IdCardStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
