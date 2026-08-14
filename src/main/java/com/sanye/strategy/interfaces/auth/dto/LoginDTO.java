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
 * account 的列归属由 {@code loginType} 显式决定（不再服务端格式判型）：当前仅开放
 * 账号密码（{@code loginType=3}），account 即用户名；手机号/验证码/第三方登录实现后对应放宽。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "登录请求")
public class LoginDTO {

    /** 账号（列归属由 loginType 决定，当前 PASSWORD=用户名） */
    @NotBlank(message = "账号不能为空")
    @Size(max = 64, message = "账号长度不能超过64")
    @Schema(description = "账号（当前仅支持用户名，语义由 loginType 决定）", example = "admin", maxLength = 64)
    private String account;

    /** 明文密码 */
    @NotBlank(message = "密码不能为空")
    @Schema(description = "明文密码", example = "Admin@123")
    private String password;

    /** 登入方式码，见 {@link com.sanye.strategy.domain.enums.LoginTypeEnum}（前端显式传，后端校验，当前仅账号密码） */
    @NotNull(message = "登入方式不能为空")
    @Schema(description = "登入方式：3-账号密码（当前仅开放）", example = "3", allowableValues = {"1", "2", "3", "4"})
    private Integer loginType;

    /** 登录渠道码，见 {@link com.sanye.strategy.domain.enums.RegisterChannelEnum}（前端显式传，后端校验，当前仅 H5/PC） */
    @NotNull(message = "登录渠道不能为空")
    @Schema(description = "登录渠道：3-H5 4-PC（当前仅开放这两个）", example = "4", allowableValues = {"3", "4"})
    private Integer registerChannel;

    /** 设备信息 */
    @Valid
    @NotNull(message = "设备信息不能为空")
    @Schema(description = "客户端设备信息")
    private DeviceInfo deviceInfo;
}
