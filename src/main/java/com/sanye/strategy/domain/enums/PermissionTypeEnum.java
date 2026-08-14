package com.sanye.strategy.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 权限资源类型枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@code ums_permission.permission_type} 字段使用，提供类型安全的权限资源类型定义：
 * <ul>
 *   <li>{@link #DIRECTORY} - 1，目录</li>
 *   <li>{@link #MENU} - 2，菜单</li>
 *   <li>{@link #BUTTON} - 3，按钮</li>
 *   <li>{@link #INTERFACE} - 4，接口</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum PermissionTypeEnum {

    /**
     * 目录
     */
    DIRECTORY(1, "目录"),

    /**
     * 菜单
     */
    MENU(2, "菜单"),

    /**
     * 按钮
     */
    BUTTON(3, "按钮"),

    /**
     * 接口
     */
    INTERFACE(4, "接口");

    /**
     * 类型码
     */
    @EnumValue
    private final int code;

    /**
     * 描述
     */
    private final String desc;

    PermissionTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 类型码
     * @return 对应枚举值，未匹配返回 null
     */
    public static PermissionTypeEnum valueOf(int code) {
        for (PermissionTypeEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}
