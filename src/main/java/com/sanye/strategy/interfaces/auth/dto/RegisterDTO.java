package com.sanye.strategy.interfaces.auth.dto;

import com.sanye.strategy.application.device.dto.DeviceInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * <p>
 * 注册请求 DTO
 * </p>
 * <p>
 * 用户类型不对外开放（注册恒为普通用户，防提权）；昵称缺省回落用户名。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "注册请求")
public class RegisterDTO {

    /** 登录账号（4-50 位字母/数字/下划线） */
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{4,50}$", message = "用户名需为4-50位字母、数字或下划线")
    @Schema(description = "登录账号", example = "zhangsan", pattern = "^[a-zA-Z0-9_]{4,50}$", minLength = 4, maxLength = 50)
    private String username;

    /** 明文密码（≥8 位，字母数字组合由业务校验） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在8-64位之间")
    @Schema(description = "明文密码", example = "Abc@12345", minLength = 8, maxLength = 64)
    private String password;

    /** 手机号（可选） */
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    @Schema(description = "手机号（可选）", example = "13800138000", pattern = "^1\\d{10}$")
    private String phone;

    /** 邮箱（可选） */
    @Email(message = "邮箱格式不正确")
    @Schema(description = "邮箱（可选）", example = "user@example.com")
    private String email;

    /** 昵称（可选） */
    @Size(max = 50, message = "昵称长度不能超过50位")
    @Schema(description = "昵称（可选，缺省回落用户名）", example = "张三", maxLength = 50)
    private String nickname;

    /** 设备信息（落库会话行 + 注册渠道推导） */
    @Valid
    @NotNull(message = "设备信息不能为空")
    @Schema(description = "客户端设备信息")
    private DeviceInfo deviceInfo;
}
