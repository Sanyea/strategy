package com.sanye.strategy.interfaces.auth.dto;

import com.sanye.strategy.application.device.dto.DeviceInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * <p>
 * 登录请求 DTO
 * </p>
 * <p>
 * account 可为手机号/邮箱/用户名（服务端判型，见 AuthService）。
 * </p>
 *
 * @author 31372
 */
@Data
public class LoginDTO {

    /** 账号（手机号/邮箱/用户名） */
    @NotBlank(message = "账号不能为空")
    @Size(max = 64, message = "账号长度不能超过64")
    private String account;

    /** 明文密码 */
    @NotBlank(message = "密码不能为空")
    private String password;

    /** 设备信息 */
    @Valid
    @NotNull(message = "设备信息不能为空")
    private DeviceInfo deviceInfo;
}
