package com.sanye.strategy.domain;

import com.sanye.strategy.common.base.SimpleBaseEntity;
import com.sanye.strategy.enums.EducationEnum;
import com.sanye.strategy.enums.IncomeLevelEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 用户扩展信息实体 — 纯 POJO，零框架依赖
 * </p>
 * <p>
 * 持久化映射见 {@link com.sanye.strategy.po.UmsUserProfilePO}。
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UmsUserProfile extends SimpleBaseEntity {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 国家
     */
    private String country;

    /**
     * 省份
     */
    private String province;

    /**
     * 城市
     */
    private String city;

    /**
     * 区县
     */
    private String district;

    /**
     * 详细地址
     */
    private String address;

    /**
     * 职业
     */
    private String occupation;

    /**
     * 学历 {@link EducationEnum}
     */
    private EducationEnum education;

    /**
     * 收入水平 {@link IncomeLevelEnum}
     */
    private IncomeLevelEnum incomeLevel;

    /**
     * 备用联系电话
     */
    private String contactPhone;

    /**
     * 备用邮箱
     */
    private String contactEmail;

    /**
     * 个性签名
     */
    private String signature;

    /**
     * 个人主页背景图
     */
    private String bgImage;

    /**
     * 扩展字段(JSON 文本，序列化/反序列化策略待定)
     */
    private String extInfo;
}
