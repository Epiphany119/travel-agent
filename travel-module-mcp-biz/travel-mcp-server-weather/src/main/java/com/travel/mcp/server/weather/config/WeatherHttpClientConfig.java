package com.travel.mcp.server.weather.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 天气服务下游 HTTP 客户端配置。
 *
 * <p>天气服务位于 MCP 请求链路的最内层，必须早于 MCP 客户端的 8 秒窗口结束，
 * 否则调用方已经断开连接后，本服务才返回降级结果，容易产生
 * {@code FluxReceive: exception observed post termination} 噪音。</p>
 */
@Configuration
public class WeatherHttpClientConfig {

    @Bean(name = "weatherRestTemplate")
    public RestTemplate weatherRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(6_000);
        return new RestTemplate(factory);
    }
}
