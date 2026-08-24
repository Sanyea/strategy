package com.sanye.strategy.infrastructure.config;

import com.sanye.strategy.infrastructure.logging.AccessLogFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <p>
 * 日志产生端 Bean 配置 — 注册 {@link AccessLogFilter}（请求轨）
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：日志相关 Spring Bean 收口（后续阶段如需慢请求埋点 Bean 亦加在此）。</li>
 *   <li>优缺点：显式 FilterRegistrationBean 可控顺序与 URL 映射；代价为多一个配置类。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Configuration
public class LoggingBeanConfig {

    /**
     * 注册接入访问日志过滤器（最高优先级，覆盖全部请求含异常路径）
     */
    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration() {
        FilterRegistrationBean<AccessLogFilter> registration =
                new FilterRegistrationBean<>(new AccessLogFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.setName("accessLogFilter");
        return registration;
    }
}