package com.travel.mcp.server.poi.service;

import com.travel.mcp.server.poi.model.AmapResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RouteService {

    private static final String WALKING_URL = "https://restapi.amap.com/v3/direction/walking";
    private static final String TRANSIT_URL = "https://restapi.amap.com/v3/direction/transit/integrated";
    private static final String DRIVING_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String BICYCLING_URL = "https://restapi.amap.com/v3/direction/bicycling";

    private final RestTemplate restTemplate;

    @Value("${travel.poi.amap-key:}")
    private String apiKey;

    public RouteService(@Qualifier("poiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public AmapResponse walking(String origin, String destination) {
        if (apiKey == null || apiKey.isBlank()) {
            return AmapResponse.fallback("高德地图API密钥未配置");
        }
        if (origin == null || destination == null) {
            return AmapResponse.fallback("步行规划需要提供起点(origin)和终点(destination)坐标");
        }

        String url = UriComponentsBuilder.fromHttpUrl(WALKING_URL)
                .queryParam("key", apiKey)
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .build()
                .toUriString();

        log.debug("步行路径规划请求: {} -> {}", origin, destination);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            return parseDirectionResponse(response, "walking");
        } catch (Exception e) {
            log.error("步行规划API调用异常", e);
            return AmapResponse.fallback("API调用异常: " + e.getMessage());
        }
    }

    public AmapResponse transit(String origin, String destination, String city, Integer strategy) {
        if (apiKey == null || apiKey.isBlank()) {
            return AmapResponse.fallback("高德地图API密钥未配置");
        }
        if (origin == null || destination == null || city == null) {
            return AmapResponse.fallback("公交规划需要提供起点(origin)、终点(destination)和城市(city)");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(TRANSIT_URL)
                .queryParam("key", apiKey)
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .queryParam("city", city)
                .queryParam("strategy", strategy != null ? strategy : 0)
                .queryParam("extensions", "base")
                .queryParam("nightflag", 0);

        String url = builder.build().toUriString();

        log.debug("公交路径规划请求: {} -> {}, city={}", origin, destination, city);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            return parseTransitResponse(response);
        } catch (Exception e) {
            log.error("公交规划API调用异常", e);
            return AmapResponse.fallback("API调用异常: " + e.getMessage());
        }
    }

    public AmapResponse driving(String origin, String destination, String waypoints, String extensions, Integer strategy) {
        if (apiKey == null || apiKey.isBlank()) {
            return AmapResponse.fallback("高德地图API密钥未配置");
        }
        if (origin == null || destination == null) {
            return AmapResponse.fallback("驾车规划需要提供起点(origin)和终点(destination)坐标");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(DRIVING_URL)
                .queryParam("key", apiKey)
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .queryParam("extensions", extensions != null ? extensions : "base")
                .queryParam("strategy", strategy != null ? strategy : 0);

        if (waypoints != null) {
            builder.queryParam("waypoints", waypoints);
        }

        String url = builder.build().toUriString();

        log.debug("驾车路径规划请求: {} -> {}", origin, destination);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            return parseDirectionResponse(response, "driving");
        } catch (Exception e) {
            log.error("驾车规划API调用异常", e);
            return AmapResponse.fallback("API调用异常: " + e.getMessage());
        }
    }

    public AmapResponse bicycling(String origin, String destination) {
        if (apiKey == null || apiKey.isBlank()) {
            return AmapResponse.fallback("高德地图API密钥未配置");
        }
        if (origin == null || destination == null) {
            return AmapResponse.fallback("骑行规划需要提供起点(origin)和终点(destination)坐标");
        }

        String url = UriComponentsBuilder.fromHttpUrl(BICYCLING_URL)
                .queryParam("key", apiKey)
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .build()
                .toUriString();

        log.debug("骑行路径规划请求: {} -> {}", origin, destination);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            return parseDirectionResponse(response, "bicycling");
        } catch (Exception e) {
            log.error("骑行规划API调用异常", e);
            return AmapResponse.fallback("API调用异常: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private AmapResponse parseDirectionResponse(Map<String, Object> response, String action) {
        if (response == null) {
            return AmapResponse.fallback(action + "路径规划API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback(action + "路径规划失败: " + info);
        }

        String routeKey = action.equals("bicycling") ? "data" : "route";
        Map<String, Object> route = (Map<String, Object>) response.get(routeKey);
        if (route == null) {
            return AmapResponse.builder()
                    .success(true)
                    .action(action)
                    .count(0)
                    .message("未找到路径数据")
                    .build();
        }

        List<AmapResponse.PathResult> paths = new ArrayList<>();

        if (action.equals("walking") || action.equals("driving") || action.equals("bicycling")) {
            Object pathsObj = route.get("paths");
            if (pathsObj instanceof List) {
                List<Map<String, Object>> pathsList = (List<Map<String, Object>>) pathsObj;
                for (Map<String, Object> pathMap : pathsList) {
                    paths.add(parsePathResult(pathMap, action));
                }
            }
        }

        return AmapResponse.builder()
                .success(true)
                .action(action)
                .count(paths.size())
                .paths(paths)
                .build();
    }

    @SuppressWarnings("unchecked")
    private AmapResponse.PathResult parsePathResult(Map<String, Object> pathMap, String action) {
        AmapResponse.PathResult.PathResultBuilder builder = AmapResponse.PathResult.builder()
                .distance(parseInt(pathMap.get("distance")))
                .duration(parseInt(pathMap.get("duration")));

        if (action.equals("driving")) {
            builder.tolls(parseInt(pathMap.get("tolls")));
            builder.tollDistance(parseInt(pathMap.get("toll_distance")));
        }

        List<Map<String, Object>> stepsList = (List<Map<String, Object>>) pathMap.get("steps");
        if (stepsList != null) {
            List<AmapResponse.PathStep> steps = stepsList.stream()
                    .map(step -> AmapResponse.PathStep.builder()
                            .instruction((String) step.get("instruction"))
                            .road((String) step.get("road"))
                            .distance(parseInt(step.get("distance")))
                            .duration(parseInt(step.get("duration")))
                            .polyline((String) step.get("polyline"))
                            .action((String) step.get("action"))
                            .build())
                    .toList();
            builder.steps(steps);
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private AmapResponse parseTransitResponse(Map<String, Object> response) {
        if (response == null) {
            return AmapResponse.fallback("公交路径规划API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback("公交路径规划失败: " + info);
        }

        Map<String, Object> route = (Map<String, Object>) response.get("route");
        if (route == null) {
            return AmapResponse.builder()
                    .success(true)
                    .action("transit")
                    .count(0)
                    .message("未找到公交路径数据")
                    .build();
        }

        List<AmapResponse.TransitResult> transits = new ArrayList<>();
        List<Map<String, Object>> transitsList = (List<Map<String, Object>>) route.get("transits");
        if (transitsList != null) {
            for (Map<String, Object> transitMap : transitsList) {
                AmapResponse.TransitResult.TransitResultBuilder tBuilder = AmapResponse.TransitResult.builder()
                        .cost(parseInt(transitMap.get("cost")))
                        .duration(parseInt(transitMap.get("duration")))
                        .walkingDistance(parseInt(transitMap.get("walking_distance")))
                        .nightflag(parseInt(transitMap.get("nightflag")));

                List<Map<String, Object>> segmentsList = (List<Map<String, Object>>) transitMap.get("segments");
                if (segmentsList != null) {
                    List<AmapResponse.TransitSegment> segments = segmentsList.stream()
                            .map(seg -> {
                                AmapResponse.TransitSegment.TransitSegmentBuilder sBuilder = AmapResponse.TransitSegment.builder();

                                Map<String, Object> walking = (Map<String, Object>) seg.get("walking");
                                if (walking != null) {
                                    sBuilder.walkingDistance(parseInt(walking.get("distance")));
                                    sBuilder.walkingDuration(parseInt(walking.get("duration")));
                                }

                                List<Map<String, Object>> buses = (List<Map<String, Object>>) seg.get("bus");
                                if (buses != null && !buses.isEmpty()) {
                                    Map<String, Object> bus = buses.get(0);
                                    sBuilder.busName((String) ((Map<String, Object>) bus.get("buslines")).get("name"));
                                    sBuilder.busType((String) ((Map<String, Object>) bus.get("buslines")).get("type"));
                                }

                                return sBuilder.build();
                            })
                            .toList();
                    tBuilder.segments(segments);
                }

                transits.add(tBuilder.build());
            }
        }

        return AmapResponse.builder()
                .success(true)
                .action("transit")
                .count(transits.size())
                .transits(transits)
                .build();
    }

    private int parseInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Integer) return (Integer) value;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
