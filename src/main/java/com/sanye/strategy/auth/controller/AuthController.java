package com.sanye.strategy.auth.controller;

import com.sanye.strategy.auth.dto.LoginDTO;
import com.sanye.strategy.auth.dto.MfaVerifyDTO;
import com.sanye.strategy.auth.dto.RefreshDTO;
import com.sanye.strategy.auth.dto.RegisterDTO;
import com.sanye.strategy.auth.dto.TokenVO;
import com.sanye.strategy.auth.service.AuthService;
import com.sanye.strategy.common.response.R;
import com.sanye.strategy.common.util.IpUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 认证端点 — 薄 Controller，只做参数接收/校验/VO 包装，业务全在 {@link AuthService}
 * </p>
 * <p>
 * 登录/注册/刷新/MFA 验证为白名单（WebMvcConfig），登出需 Bearer accessToken（拦截器鉴权）。
 * 登录遇 MFA_REQUIRED 返回 403 + 挑战凭证（MfaChallengeVO），客户端凭 tempToken 调 verify。
 * </p>
 *
 * @author 31372
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 注册
     */
    @PostMapping("/register")
    public R<TokenVO> register(@Valid @RequestBody RegisterDTO dto, HttpServletRequest request) {
        return R.ok(authService.register(dto, IpUtils.getClientIp(request)));
    }

    /**
     * 登录
     */
    @PostMapping("/login")
    public R<TokenVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        return R.ok(authService.login(dto, IpUtils.getClientIp(request)));
    }

    /**
     * MFA 二次验证（登录 403 挑战凭证 + OTP，白名单）
     */
    @PostMapping("/mfa/verify")
    public R<TokenVO> verifyMfa(@Valid @RequestBody MfaVerifyDTO dto, HttpServletRequest request) {
        return R.ok(authService.verifyMfa(dto, IpUtils.getClientIp(request)));
    }

    /**
     * 刷新双 Token（轮换）
     */
    @PostMapping("/refresh")
    public R<TokenVO> refresh(@Valid @RequestBody RefreshDTO dto) {
        return R.ok(authService.refresh(dto));
    }

    /**
     * 登出（需登录）
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }
}
