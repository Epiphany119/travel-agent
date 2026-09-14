package com.travel.a2a.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * A2A 编排运行时参数。
 *
 * <p>默认值采用分层超时：单工具 10 秒、整组工具 30 秒、SSE 5 分钟。
 * 单工具超时后仍允许其它工具正常返回，整组超时只作为最后一道保险。</p>
 */
@Component
@ConfigurationProperties(prefix = "a2a.runtime")
public class A2aRuntimeProperties {

    private Duration toolTimeout = Duration.ofSeconds(10);
    private Duration aggregateTimeout = Duration.ofSeconds(30);
    private Duration sseTimeout = Duration.ofMinutes(5);

    public Duration getToolTimeout() {
        return toolTimeout;
    }

    public void setToolTimeout(Duration toolTimeout) {
        this.toolTimeout = toolTimeout;
    }

    public Duration getAggregateTimeout() {
        return aggregateTimeout;
    }

    public void setAggregateTimeout(Duration aggregateTimeout) {
        this.aggregateTimeout = aggregateTimeout;
    }

    public Duration getSseTimeout() {
        return sseTimeout;
    }

    public void setSseTimeout(Duration sseTimeout) {
        this.sseTimeout = sseTimeout;
    }
}
