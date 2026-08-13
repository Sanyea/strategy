package com.sanye.strategy.interfaces.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * <p>
 * 双 Token 响应 VO
 * </p>
 * <p>
 * 客户端保存：accessToken 走内存/Header，refreshToken 走安全存储；
 * 不返回任何敏感字段（userId/userType 经 /users/me 获取）。
 * </p>
 *
 * @author 31372
 */
@Data
@Schema(description = "双 Token 响应")
public class TokenVO {

    /** JWT accessToken（30min） */
    @Schema(description = "JWT accessToken（有效期 30 分钟）", example = "eyJhbGciOi...")
    private String accessToken;

    /** 不透明 refreshToken（14 天，一次性） */
    @Schema(description = "不透明 refreshToken（14 天有效期，一次性）")
    private String refreshToken;

    /** accessToken 有效期（秒），供客户端预判刷新时机 */
    @Schema(description = "accessToken 有效期（秒），供客户端预判刷新时机", example = "1800")
    private Integer accessExpiresIn;
}
