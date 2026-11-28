package com.travel.module.itinerary.biz.planning.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 高德地图实时路径规划适配器占位骨架。
 * 默认不启用（仅当配置 travel.planning.routing.provider=amap 时激活），
 * 当前方法安全委托给 PlaceholderTransportRoutingProvider，待正式接入高德路径规划 v5 API 时替换实现即可。
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "travel.planning.routing.provider", havingValue = "amap")
public class AmapTransportRoutingAdapterPlaceholder implements TransportRoutingProvider {

    private final PlaceholderTransportRoutingProvider fallbackProvider;

    @Override
    public String providerCode() {
        return "AMAP_V5_ADAPTER_PLACEHOLDER";
    }

    @Override
    public List<DayTransportPlan> estimateDays(String destination, List<DayStopsInput> days) {
        log.info("Amap routing adapter placeholder invoked for destination={}, delegating to local placeholder", destination);
        return fallbackProvider.estimateDays(destination, days);
    }

    @Override
    public RouteOptimizationResult optimizeDayOrder(String destination, DayStopsInput dayInput) {
        return fallbackProvider.optimizeDayOrder(destination, dayInput);
    }
}
