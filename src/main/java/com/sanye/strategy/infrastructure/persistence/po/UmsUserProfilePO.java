package com.sanye.strategy.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sanye.strategy.common.base.SimpleBasePO;
import com.sanye.strategy.domain.enums.EducationEnum;
import com.sanye.strategy.domain.enums.IncomeLevelEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 用户扩展信息持久化对象（PO）— Mapper 操作对象，ORM 耦合集中于此
 * </p>
 * <p>
 * 对应领域实体 {@link com.sanye.strategy.domain.UmsUserProfile}，字段一致，差异仅在 MP 映射注解。
 * </p>
 * <p>
 * {@code ext_info} 为 JSON 列，以 {@code String} 存取（JSON 文本），序列化/反序列化策略待定。
 * 注：MP 的 {@code JacksonTypeHandler} 基于 Jackson 2，而 Spring Boot 4 默认 Jackson 3
 * （{@code tools.jackson.*} 命名空间），二者不兼容，故暂不挂类型处理器。
 * </p>
 *
 * @author 31372
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ums_user_profile")
public class UmsUserProfilePO extends SimpleBasePO {

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
     * 扩展字段(JSON 文本)
     */
    private String extInfo;
}
