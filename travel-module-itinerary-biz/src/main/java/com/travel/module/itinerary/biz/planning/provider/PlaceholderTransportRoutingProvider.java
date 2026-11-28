package com.travel.module.itinerary.biz.planning.provider;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 第一阶段地图距离与交通规划占位实现（不调用外部高德/地图平台）。
 * 基于节点名称、地址关键词相似度与确定性哈希估算合理距离、耗时与推荐出行方式，
 * 保证同一行程多次刷新结果稳定；后续可直接替换为真实高德路径规划实现。
 */
@Component
public class PlaceholderTransportRoutingProvider implements TransportRoutingProvider {

    @Override
    public String providerCode() {
        return "PLACEHOLDER_ROUTING";
    }

    @Override
    public List<DayTransportPlan> estimateDays(String destination, List<DayStopsInput> days) {
        List<DayTransportPlan> results = new ArrayList<>();
        if (days == null) {
            return results;
        }
        for (DayStopsInput dayInput : days) {
            List<StopInput> stops = dayInput.stops() == null ? List.of() : dayInput.stops();
            List<TransportSegment> segments = new ArrayList<>();
            double totalDistance = 0.0;
            int totalMinutes = 0;
            int totalFare = 0;

            for (int i = 0; i < stops.size() - 1; i++) {
                StopInput from = stops.get(i);
                StopInput to = stops.get(i + 1);
                TransportSegment seg = estimateSegment(destination, from, to);
                segments.add(seg);
                totalDistance += seg.distanceKm();
                totalMinutes += seg.durationMinutes();
                totalFare += seg.estimatedFare();
            }

            double roundedDist = Math.round(totalDistance * 10.0) / 10.0;
            String warning = null;
            if (totalMinutes > 95 || roundedDist > 28.0) {
                warning = "当天跨区通勤偏长（约 " + roundedDist + " km / " + totalMinutes + " 分钟），建议合并同片区景点以减少折返";
            }
            results.add(new DayTransportPlan(
                    dayInput.day(),
                    roundedDist,
                    totalMinutes,
                    totalFare,
                    warning,
                    segments
            ));
        }
        return results;
    }

    private TransportSegment estimateSegment(String destination, StopInput from, StopInput to) {
        String fromName = safe(from.name());
        String toName = safe(to.name());
        String fromLoc = safe(from.location());
        String toLoc = safe(to.location());

        boolean sameDistrict = hasSharedDistrict(fromLoc, toLoc);
        boolean mealNearby = "meal".equalsIgnoreCase(from.type()) || "meal".equalsIgnoreCase(to.type());

        int hash = Math.abs((safe(destination) + "|" + fromName + "->" + toName).hashCode());
        double distanceKm;
        if (sameDistrict && mealNearby) {
            distanceKm = 0.6 + (hash % 12) * 0.1; // 0.6 - 1.7 km
        } else if (sameDistrict) {
            distanceKm = 1.2 + (hash % 28) * 0.1; // 1.2 - 3.9 km
        } else if (mealNearby) {
            distanceKm = 1.8 + (hash % 32) * 0.1; // 1.8 - 4.9 km
        } else {
            distanceKm = 3.2 + (hash % 65) * 0.1; // 3.2 - 9.6 km
        }
        distanceKm = Math.round(distanceKm * 10.0) / 10.0;

        String mode;
        int durationMin;
        int fare;
        String tip;

        if (distanceKm <= 1.4) {
            mode = "步行";
            durationMin = Math.max(6, (int) Math.round(distanceKm * 13));
            fare = 0;
            tip = "距离很近，沿途步行即可到达（占位估算）";
        } else if (distanceKm <= 4.5) {
            mode = "地铁/打车";
            durationMin = Math.max(10, (int) Math.round(distanceKm * 4.2 + 5));
            fare = Math.max(13, (int) Math.round(11 + distanceKm * 2.4));
            tip = "地铁约 ¥3–4，多人同行打车更省时（占位估算）";
        } else {
            mode = "地铁优先 / 网约车";
            durationMin = Math.max(18, (int) Math.round(distanceKm * 3.6 + 8));
            fare = Math.max(18, (int) Math.round(13 + distanceKm * 2.6));
            tip = "跨片区移动，高峰期建议优先轨道交通（占位估算）";
        }

        return new TransportSegment(
                fromName,
                toName,
                distanceKm,
                durationMin,
                mode,
                fare,
                tip,
                providerCode()
        );
    }

    private boolean hasSharedDistrict(String loc1, String loc2) {
        if (loc1.isBlank() || loc2.isBlank()) {
            return false;
        }
        String d1 = extractDistrict(loc1);
        String d2 = extractDistrict(loc2);
        return !d1.isBlank() && d1.equals(d2);
    }

    private String extractDistrict(String loc) {
        int idx = loc.indexOf('区');
        if (idx >= 2) {
            return loc.substring(Math.max(0, idx - 2), idx + 1);
        }
        idx = loc.indexOf('县');
        if (idx >= 2) {
            return loc.substring(Math.max(0, idx - 2), idx + 1);
        }
        return "";
    }

    private String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
