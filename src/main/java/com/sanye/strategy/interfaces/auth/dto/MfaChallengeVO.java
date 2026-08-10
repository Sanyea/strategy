package com.sanye.strategy.interfaces.auth.dto;

import lombok.Data;

/**
 * <p>
 * MFA 挑战凭证响应 VO
 * </p>
 * <p>
 * 登录 mfa=1 时随 403 MFA_REQUIRED 返回；tempToken 为 5min 短时效瞬态凭证，
 * GETDEL 单次消费后即失效，客户端凭此调用 /auth/mfa/verify。
 * </p>
 *
 * @author 31372
 */
@Data
public class MfaChallengeVO {

    /** 挑战凭证（32B hex） */
    private String tempToken;

    /** 有效秒数（TTL，本批 300） */
    private Integer expiresIn;
}
