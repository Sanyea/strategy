package com.sanye.strategy.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * <p>
 * 客户端 IP 工具
 * </p>
 * <p>
 * 优先取 X-Forwarded-For 首段（经代理），否则取 remoteAddr。
 * </p>
 *
 * @author 31372
 */
public final class IpUtils {

    private IpUtils() {
    }

    public static String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
