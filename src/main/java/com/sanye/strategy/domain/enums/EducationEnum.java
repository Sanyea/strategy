package com.sanye.strategy.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import lombok.Getter;

/**
 * <p>
 * 学历枚举（阿里规范）
 * </p>
 * <p>
 * 配套 {@link com.sanye.strategy.domain.UmsUserProfile#getEducation()} 字段使用，提供类型安全的学历定义：
 * <ul>
 *   <li>{@link #UNKNOWN} - 0，未知</li>
 *   <li>{@link #JUNIOR_HIGH} - 1，初中</li>
 *   <li>{@link #SENIOR_HIGH} - 2，高中</li>
 *   <li>{@link #COLLEGE} - 3，大专</li>
 *   <li>{@link #BACHELOR} - 4，本科</li>
 *   <li>{@link #MASTER} - 5，硕士</li>
 *   <li>{@link #DOCTOR} - 6，博士</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Getter
public enum EducationEnum {

    /**
     * 未知
     */
    UNKNOWN(0, "未知"),

    /**
     * 初中
     */
    JUNIOR_HIGH(1, "初中"),

    /**
     * 高中
     */
    SENIOR_HIGH(2, "高中"),

    /**
     * 大专
     */
    COLLEGE(3, "大专"),

    /**
     * 本科
     */
    BACHELOR(4, "本科"),

    /**
     * 硕士
     */
    MASTER(5, "硕士"),

    /**
     * 博士
     */
    DOCTOR(6, "博士");

    /**
     * 状态码
     */
    @EnumValue
    private final Integer code;

    /**
     * 描述
     */
    private final String desc;

    EducationEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举值，未匹配返回 null
     */
    public static EducationEnum valueOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (EducationEnum education : values()) {
            if (education.code.equals(code)) {
                return education;
            }
        }
        return null;
    }
}
