package com.sanye.strategy.infrastructure.config;

import com.sanye.strategy.infrastructure.interceptor.TokenAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p>
 * Web MVC 配置 — 注册认证拦截器与白名单
 * </p>
 * <p>
 * 白名单路径不经过 {@link TokenAuthInterceptor}（放行）：登录/注册/刷新/MFA 验证发生在签发 token 前，
 * 必须免认证；actuator 健康检查与 /error 亦放行。白名单集中维护于此。
 * </p>
 *
 * @author 31372
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /** 白名单：无需登录即可访问 */
    private static final String[] WHITE_LIST = {
            "/auth/login", "/auth/register", "/auth/refresh", "/auth/mfa/verify",
            "/actuator/**", "/error",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    private final TokenAuthInterceptor tokenAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenAuthInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(WHITE_LIST);
    }
}
