package com.sanye.strategy.interfaces.auth.dto;

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
public class RefreshDTO {

    /** 不透明 refreshToken */
    @NotBlank(message = "refreshToken 不能为空")
    @Size(max = 128, message = "refreshToken 长度不能超过128")
    private String refreshToken;

    /** 设备 ID（与会话行比对，防跨设备盗用） */
    @NotBlank(message = "deviceId 不能为空")
    private String deviceId;
}
