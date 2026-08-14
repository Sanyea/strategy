package com.sanye.strategy.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 角色状态枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@code ums_role.status} 字段使用，提供类型安全的角色状态定义：
 * <ul>
 *   <li>{@link #DISABLED} - 0，停用（角色失效，绑定用户不再生效）</li>
 *   <li>{@link #NORMAL} - 1，正常</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum RoleStatusEnum {

    /**
     * 停用
     */
    DISABLED(0, "停用"),

    /**
     * 正常
     */
    NORMAL(1, "正常");

    /**
     * 状态码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    RoleStatusEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举值，未匹配返回 null
     */
    public static RoleStatusEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (RoleStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
