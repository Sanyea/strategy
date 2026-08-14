package com.sanye.strategy.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 数据权限范围枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@code ums_role.data_scope} 字段使用，提供类型安全的数据权限范围定义：
 * <ul>
 *   <li>{@link #ALL} - 1，全部数据</li>
 *   <li>{@link #SELF} - 2，仅本人数据</li>
 *   <li>{@link #DEPT} - 3，本部门数据（依赖后续部门表 ums_dept）</li>
 *   <li>{@link #DEPT_AND_BELOW} - 4，本部门及以下（依赖后续部门表 ums_dept）</li>
 *   <li>{@link #CUSTOM} - 5，自定义（依赖后续角色-数据域关联表）</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum DataScopeEnum {

    /**
     * 全部数据
     */
    ALL(1, "全部数据"),

    /**
     * 仅本人数据
     */
    SELF(2, "仅本人数据"),

    /**
     * 本部门数据（需部门表）
     */
    DEPT(3, "本部门数据"),

    /**
     * 本部门及以下数据（需部门表）
     */
    DEPT_AND_BELOW(4, "本部门及以下数据"),

    /**
     * 自定义数据（需角色-数据域关联表）
     */
    CUSTOM(5, "自定义数据");

    /**
     * 状态码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    DataScopeEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举值，未匹配返回 null
     */
    public static DataScopeEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (DataScopeEnum scope : values()) {
            if (scope.code.equals(code)) {
                return scope;
            }
        }
        return null;
    }
}
