package com.travel.web.config;

import com.travel.auth.service.TokenService;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/**
 * Web MVC 配置 - 静态资源处理和CORS
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final TokenService tokenService;
    public WebMvcConfig(TokenService tokenService) { this.tokenService = tokenService; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(tokenService));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 配置 /uploads/** 路径映射到用户家目录下的 travel-agent-uploads 目录
        // 支持 avatar, inspiration, journey 等所有子目录
        String uploadPath = System.getProperty("user.home") + "/travel-agent-uploads/";

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadPath);
    }
}
