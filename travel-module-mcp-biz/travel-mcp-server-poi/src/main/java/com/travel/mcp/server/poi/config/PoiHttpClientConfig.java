package com.travel.mcp.server.poi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 高德地图下游 HTTP 客户端配置。
 *
 * <p>POI 服务是 MCP 请求链路的内层服务，使用短于 MCP 客户端窗口的上游超时，
 * 让服务有机会返回结构化降级结果。</p>
 */
@Configuration
public class PoiHttpClientConfig {

    @Bean(name = "poiRestTemplate")
    public RestTemplate poiRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(6_000);
        return new RestTemplate(factory);
    }
}
