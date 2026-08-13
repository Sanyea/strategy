package com.sanye.strategy.interfaces.auth.dto;

import com.sanye.strategy.application.device.dto.DeviceInfo;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "登录请求")
public class LoginDTO {

    /** 账号（手机号/邮箱/用户名） */
    @NotBlank(message = "账号不能为空")
    @Size(max = 64, message = "账号长度不能超过64")
    @Schema(description = "账号（手机号/邮箱/用户名）", example = "admin", maxLength = 64)
    private String account;

    /** 明文密码 */
    @NotBlank(message = "密码不能为空")
    @Schema(description = "明文密码", example = "Admin@123")
    private String password;

    /** 设备信息 */
    @Valid
    @NotNull(message = "设备信息不能为空")
    @Schema(description = "客户端设备信息")
    private DeviceInfo deviceInfo;
}
