package com.sanye.strategy.infrastructure.logging;

import com.sanye.strategy.common.util.IpMaskUtils;
import com.sanye.strategy.common.util.IpUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * <p>
 * 接入访问日志过滤器 — 请求轨（category=access）产生端
 * </p>
 * <p>
 * 每请求完成后经 {@code ACCESS} logger 输出一行结构化日志（method/uri/status/耗时/掩码 IP），
 * 由 logback-spring.xml 路由至 access.log。IP 按规格 6.3「请求轨接入访问」末段掩码（产生端完成）。
 * 不记录请求体/响应体（防大报文，规格 5.1；审计所需参数另走 ums_oper_log）。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：Servlet 过滤器（OncePerRequestFilter），请求轨唯一产生端。</li>
 *   <li>优缺点：全路径覆盖（含白名单）；代价为与 Actuator/Swagger 请求也会记录
 *       （量大时阶段1 经 Vector 采样，规格 4.1 接入访问可采样）。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("ACCESS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            ACCESS_LOG.atInfo()
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("uri", request.getRequestURI())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("costMs", System.currentTimeMillis() - start)
                    .addKeyValue("ip", IpMaskUtils.maskLastSegment(IpUtils.getClientIp(request)))
                    .log("http access");
        }
    }
}