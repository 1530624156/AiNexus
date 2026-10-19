package com.ainexus.gateway.config;

import com.ainexus.gateway.auth.AdminAuthInterceptor;
import com.ainexus.gateway.auth.ConsoleAuthInterceptor;
import com.ainexus.gateway.auth.InternalAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置: 内部鉴权拦截器(/v1/**) + CORS。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final InternalAuthInterceptor internalAuthInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;
    private final ConsoleAuthInterceptor consoleAuthInterceptor;
    private final GatewayProperties gatewayProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalAuthInterceptor)
                .addPathPatterns("/v1/**");
        registry.addInterceptor(consoleAuthInterceptor)
                .addPathPatterns("/api/console/**", "/api/auth/me");
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/admin/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String origins = gatewayProperties.getCors().getAllowedOrigins();
        String[] allowedOrigins = (origins == null || origins.isBlank())
                ? new String[]{"*"}
                : origins.split(",");
        registry.addMapping("/v1/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
        registry.addMapping("/api/admin/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
        registry.addMapping("/api/auth/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
        registry.addMapping("/api/console/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}