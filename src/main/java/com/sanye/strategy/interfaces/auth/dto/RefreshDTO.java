package com.sanye.strategy.interfaces.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * <p>
 * 刷新 token 请求 DTO
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "刷新 Token 请求")
public class RefreshDTO {

    /** 不透明 refreshToken */
    @NotBlank(message = "refreshToken 不能为空")
    @Size(max = 128, message = "refreshToken 长度不能超过128")
    @Schema(description = "不透明 refreshToken", maxLength = 128)
    private String refreshToken;

    /** 设备 ID（与会话行比对，防跨设备盗用） */
    @NotBlank(message = "deviceId 不能为空")
    @Schema(description = "设备唯一ID，与会话行比对防跨设备盗用")
    private String deviceId;
}
