package com.travel.agent.config;

import com.travel.common.http.HttpClientSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 通用 Bean 配置
 *
 * <p>PR#1 调整：移除 {@code getWeatherSync} / {@code searchPoiSync} 这两个死方法
 * （项目中无人调用），对应的格式化辅助方法也一并删除；保留 {@code RestTemplate}
 * 供 {@link com.travel.common.tool.WeatherTool} / {@link com.travel.common.tool.PoiTool} 注入使用。</p>
 */
@Configuration
public class ToolConfig {

    /**
     * RestTemplate Bean，用于HTTP请求
     */
    @Bean
    public RestTemplate restTemplate() {
        return HttpClientSupport.newRestTemplate();
    }
}
