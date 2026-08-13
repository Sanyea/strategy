package com.sanye.strategy.application.device.dto;

import com.sanye.strategy.domain.enums.DeviceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "客户端设备信息")
public class DeviceInfo {

    /**
     * 设备类型码，见 {@link DeviceTypeEnum}（1-手机 2-平板 3-PC 4-小程序）
     */
    @Schema(description = "设备类型：1-手机 2-平板 3-PC 4-小程序", example = "3", allowableValues = {"1", "2", "3", "4"})
    private Integer deviceType;

    /**
     * 操作系统
     */
    @Schema(description = "操作系统", example = "Windows 11")
    private String deviceOs;

    /**
     * 设备品牌
     */
    @Schema(description = "设备品牌", example = "Dell")
    private String deviceBrand;

    /**
     * 设备型号
     */
    @Schema(description = "设备型号", example = "XPS 15")
    private String deviceModel;

    /**
     * 设备唯一ID
     */
    @NotBlank(message = "设备ID不能为空")
    @Schema(description = "设备唯一ID", example = "device-uuid-xxx")
    private String deviceId;

    /**
     * APP版本
     */
    @Schema(description = "APP 版本号", example = "1.2.0")
    private String appVersion;
}
