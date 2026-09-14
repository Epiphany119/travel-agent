package com.travel.mcp.client.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * MCP Client 配置类。
 * 
 * <p>管理各 MCP Server 的 URL 配置，支持通过环境变量覆盖。</p>
 */
@Component
@ConfigurationProperties(prefix = "travel.mcp")
public class McpClientConfig {

    /**
     * 天气 MCP Server URL
     */
    private String weatherUrl = "http://localhost:8081";

    /**
     * POI MCP Server URL
     */
    private String poiUrl = "http://localhost:8082";

    /**
     * 餐饮 MCP Server URL
     */
    private String mealUrl = "http://localhost:8083";

    /**
     * 预算 MCP Server URL
     */
    private String budgetUrl = "http://localhost:8084";

    /**
     * 行程规划 MCP Server URL
     */
    private String itineraryUrl = "http://localhost:8085";

    /**
     * TCP 建连超时。连接失败应尽快降级，不应占住一个编排线程等待网络重试。
     */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * Reactor Netty 响应/读超时。
     */
    private Duration responseTimeout = Duration.ofSeconds(8);

    /**
     * MCP 普通请求的应用层超时。比网络读超时多 1 秒，避免两个计时器同时结束。
     */
    private Duration requestTimeout = Duration.ofSeconds(9);

    /**
     * MCP Server 信息探测超时。探测失败不影响应用启动。
     */
    private Duration serverInfoTimeout = Duration.ofSeconds(3);

    public String getWeatherUrl() {
        return weatherUrl;
    }

    public void setWeatherUrl(String weatherUrl) {
        this.weatherUrl = weatherUrl;
    }

    public String getPoiUrl() {
        return poiUrl;
    }

    public void setPoiUrl(String poiUrl) {
        this.poiUrl = poiUrl;
    }

    public String getMealUrl() {
        return mealUrl;
    }

    public void setMealUrl(String mealUrl) {
        this.mealUrl = mealUrl;
    }

    public String getBudgetUrl() {
        return budgetUrl;
    }

    public void setBudgetUrl(String budgetUrl) {
        this.budgetUrl = budgetUrl;
    }

    public String getItineraryUrl() {
        return itineraryUrl;
    }

    public void setItineraryUrl(String itineraryUrl) {
        this.itineraryUrl = itineraryUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getServerInfoTimeout() {
        return serverInfoTimeout;
    }

    public void setServerInfoTimeout(Duration serverInfoTimeout) {
        this.serverInfoTimeout = serverInfoTimeout;
    }
}
