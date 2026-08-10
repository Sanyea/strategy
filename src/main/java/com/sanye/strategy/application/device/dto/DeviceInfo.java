package com.sanye.strategy.application.device.dto;

import com.sanye.strategy.domain.enums.DeviceTypeEnum;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * <p>
 * 登录设备信息（客户端上报，login_ip 服务端注入不入此对象）
 * </p>
 * <p>
 * 注册/登录/MFA 验证请求体均含本对象，供会话行落库与注册渠道推导。
 * </p>
 *
 * @author 31372
 */
@Data
public class DeviceInfo {

    /**
     * 设备类型码，见 {@link DeviceTypeEnum}（1-手机 2-平板 3-PC 4-小程序）
     */
    private Integer deviceType;

    /**
     * 操作系统
     */
    private String deviceOs;

    /**
     * 设备品牌
     */
    private String deviceBrand;

    /**
     * 设备型号
     */
    private String deviceModel;

    /**
     * 设备唯一ID
     */
    @NotBlank(message = "设备ID不能为空")
    private String deviceId;

    /**
     * APP版本
     */
    private String appVersion;
}
