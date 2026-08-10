package com.sanye.strategy.interfaces.auth.dto;

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
public class TokenVO {

    /** JWT accessToken（30min） */
    private String accessToken;

    /** 不透明 refreshToken（14 天，一次性） */
    private String refreshToken;

    /** accessToken 有效期（秒），供客户端预判刷新时机 */
    private Integer accessExpiresIn;
}
