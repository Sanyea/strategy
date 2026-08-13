package com.sanye.strategy.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <p>
 * SpringDoc OpenAPI 3.0 配置
 * </p>
 * <p>
 * 为前端 @umijs/openapi 代码生成提供符合 OpenAPI 3.0 规范的 API 文档。
 * Swagger UI 默认路径：{@code /swagger-ui.html}；
 * OpenAPI JSON 规范：{@code /v3/api-docs}。
 * </p>
 * <p>
 * 安全方案：Bearer JWT — 前端在请求头中携带 {@code Authorization: Bearer <accessToken>}，
 * Swagger UI 中可通过右上角 Authorize 按钮填入 token 进行在线调试。
 * </p>
 *
 * @author 31372
 */
@Configuration
public class SpringDocConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Strategy API")
                        .version("1.0.0")
                        .description("策略系统后端 API 文档")
                        .contact(new Contact()
                                .name("sanye")
                                .email("admin@sanye.com")))
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("BearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("填入 accessToken（不含 Bearer 前缀），格式：<accessToken>")));
    }
}