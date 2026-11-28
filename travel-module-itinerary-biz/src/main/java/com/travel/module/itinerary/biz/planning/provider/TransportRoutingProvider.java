package com.travel.module.itinerary.biz.planning.provider;

import java.util.List;

/**
 * 地图距离与交通路径规划提供者接口。
 * 第一阶段由占位实现提供结构化交通估算，第二阶段无缝替换为高德地图路径规划 v5 API。
 */
public interface TransportRoutingProvider {

    String providerCode();

    List<DayTransportPlan> estimateDays(String destination, List<DayStopsInput> days);

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
}
