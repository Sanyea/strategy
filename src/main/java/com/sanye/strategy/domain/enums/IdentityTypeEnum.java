package com.sanye.strategy.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 第三方认证类型枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@link com.sanye.strategy.domain.UmsUserAuth#getIdentityType()} 字段使用，提供类型安全的第三方认证类型定义：
 * <ul>
 *   <li>{@link #WX_MP} - WX_MP，微信小程序</li>
 *   <li>{@link #WX_OPEN} - WX_OPEN，微信公众号</li>
 *   <li>{@link #ALIPAY} - ALIPAY，支付宝</li>
 *   <li>{@link #APPLE} - APPLE，Apple</li>
 *   <li>{@link #WEIBO} - WEIBO，微博</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum IdentityTypeEnum {

    /**
     * 微信小程序
     */
    WX_MP("WX_MP", "微信小程序"),

    /**
     * 微信公众号
     */
    WX_OPEN("WX_OPEN", "微信公众号"),

    /**
     * 支付宝
     */
    ALIPAY("ALIPAY", "支付宝"),

    /**
     * Apple
     */
    APPLE("APPLE", "Apple"),

    /**
     * 微博
     */
    WEIBO("WEIBO", "微博");

    /**
     * 认证类型
     */
    @EnumValue
    private final String code;

    /**
     * 描述
     */
    private final String desc;

    IdentityTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     * <p>
     * 枚举自带 {@link #valueOf(String)} 按常量名查找，此处改用 {@code valueOfCode} 按 {@code @EnumValue} 映射码查找，
     * 避免与内置 {@code valueOf(String)} 签名冲突。
     * </p>
     *
     * @param code 认证类型码
     * @return 对应枚举值，未匹配返回 null
     */
    public static IdentityTypeEnum valueOfCode(String code) {
        if (code == null) {
            return null;
        }
        for (IdentityTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
