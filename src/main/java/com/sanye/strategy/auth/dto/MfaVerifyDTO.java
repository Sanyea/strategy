package com.sanye.strategy.auth.dto;

import com.sanye.strategy.device.dto.DeviceInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * <p>
 * MFA 二次验证请求 DTO
 * </p>
 * <p>
 * tempToken + OTP 双要素：tempToken 为登录步骤 5 密码校验通过后签发的挑战凭证（绑定账号/设备，5min 一次性）；
 * 本接口不再传密码/账号——userId 由挑战绑定解出，密码因子已在登录时校验。
 * deviceInfo 供会话行落库 + deviceId 与挑战绑定比对（防跨设备复用）。
 * </p>
 *
 * @author 31372
 */
@Data
public class MfaVerifyDTO {

    /** 挑战凭证（登录 403 MFA_REQUIRED 响应携带） */
    @NotBlank(message = "挑战凭证不能为空")
    private String tempToken;

    /** 6 位 TOTP 验证码 */
    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为6位数字")
    private String code;

    /** 设备信息（会话行落库 + deviceId 比对） */
    @Valid
    @NotNull(message = "设备信息不能为空")
    private DeviceInfo deviceInfo;
}
