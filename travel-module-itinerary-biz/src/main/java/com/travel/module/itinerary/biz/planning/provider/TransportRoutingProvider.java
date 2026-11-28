package com.travel.module.itinerary.biz.planning.provider;

import java.util.List;

/**
 * 地图距离与交通路径规划提供者接口。
 * 第一/二阶段由占位实现提供结构化交通估算与同片区顺路重排，后续通过配置切换高德路径规划 v5 API。
 */
public interface TransportRoutingProvider {

    String providerCode();

    List<DayTransportPlan> estimateDays(String destination, List<DayStopsInput> days);

    /**
     * 按行政区/片区聚合并重排单日节点顺序，减少跨区折返。
     */
    RouteOptimizationResult optimizeDayOrder(String destination, DayStopsInput dayInput);

    record StopInput(String name, String location, String type) {}

    record DayStopsInput(int day, List<StopInput> stops) {}

    record TransportSegment(
            String fromName,
            String toName,
            double distanceKm,
            int durationMinutes,
            String recommendedMode,
            int estimatedFare,
            String tip,
            String dataSource
    ) {}

    record DayTransportPlan(
            int day,
            double totalDistanceKm,
            int totalDurationMinutes,
            int totalTransitCost,
            String routeWarning,
            List<TransportSegment> segments
    ) {}

    record RouteOptimizationResult(
            int day,
            boolean reordered,
            double originalDistanceKm,
            double optimizedDistanceKm,
            int savedMinutes,
            String summary,
            List<StopInput> optimizedStops,
            DayTransportPlan optimizedPlan,
            String dataSource
    ) {}
}
