package com.sanye.strategy.interfaces.auth;

import com.sanye.strategy.interfaces.auth.dto.LoginDTO;
import com.sanye.strategy.interfaces.auth.dto.MfaVerifyDTO;
import com.sanye.strategy.interfaces.auth.dto.RefreshDTO;
import com.sanye.strategy.interfaces.auth.dto.RegisterDTO;
import com.sanye.strategy.interfaces.auth.dto.TokenVO;
import com.sanye.strategy.application.auth.AuthService;
import com.sanye.strategy.common.response.R;
import com.sanye.strategy.common.util.IpUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "auth", description = "用户注册、登录、Token 刷新、登出及 MFA 二次验证")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "用户注册", description = "创建新用户账号，返回 JWT 双 Token")
    @PostMapping("/register")
    public R<TokenVO> register(@Valid @RequestBody RegisterDTO dto, HttpServletRequest request) {
        return R.ok(authService.register(dto, IpUtils.getClientIp(request)));
    }

    @Operation(summary = "用户登录", description = "账号密码登录，若开启 MFA 则返回 403 + 挑战凭证")
    @PostMapping("/login")
    public R<TokenVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        return R.ok(authService.login(dto, IpUtils.getClientIp(request)));
    }

    @Operation(summary = "MFA 二次验证", description = "提交 TOTP 验证码完成二次认证，返回双 Token")
    @PostMapping("/mfa/verify")
    public R<TokenVO> verifyMfa(@Valid @RequestBody MfaVerifyDTO dto, HttpServletRequest request) {
        return R.ok(authService.verifyMfa(dto, IpUtils.getClientIp(request)));
    }

    @Operation(summary = "刷新 Token", description = "使用 refreshToken 轮换新的双 Token（一次性）")
    @PostMapping("/refresh")
    public R<TokenVO> refresh(@Valid @RequestBody RefreshDTO dto) {
        return R.ok(authService.refresh(dto));
    }

    @Operation(summary = "登出", description = "使当前 accessToken 失效，销毁 refreshToken 与会话")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }
}
