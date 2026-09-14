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
public class DistanceService {

    private static final String DISTANCE_URL = "https://restapi.amap.com/v3/distance";

    private final RestTemplate restTemplate;

    @Value("${travel.poi.amap-key:}")
    private String apiKey;

    public DistanceService(@Qualifier("poiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public AmapResponse distance(String origins, String destination, Integer type) {
        if (apiKey == null || apiKey.isBlank()) {
            return AmapResponse.fallback("高德地图API密钥未配置");
        }
        if (origins == null || destination == null) {
            return AmapResponse.fallback("距离测量需要提供起点(origins)和终点(destination)");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(DISTANCE_URL)
                .queryParam("key", apiKey)
                .queryParam("origins", origins)
                .queryParam("destination", destination)
                .queryParam("type", type != null ? type : 1);

        String url = builder.build().toUriString();

        log.debug("距离测量请求: {} -> {}", origins, destination);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            return parseDistanceResponse(response);
        } catch (Exception e) {
            log.error("距离测量API调用异常", e);
            return AmapResponse.fallback("API调用异常: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private AmapResponse parseDistanceResponse(Map<String, Object> response) {
        if (response == null) {
            return AmapResponse.fallback("距离测量API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback("距离测量失败: " + info);
        }

        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        if (results == null || results.isEmpty()) {
            return AmapResponse.builder()
                    .success(true)
                    .action("distance")
                    .count(0)
                    .message("未找到距离数据")
                    .build();
        }

        List<AmapResponse.DistanceResult> distanceResults = results.stream()
                .map(r -> AmapResponse.DistanceResult.builder()
                        .origin((String) r.get("origin"))
                        .destination((String) r.get("destination"))
                        .distance(parseInt(r.get("distance")))
                        .duration(parseInt(r.get("duration")))
                        .build())
                .toList();

        return AmapResponse.builder()
                .success(true)
                .action("distance")
                .count(distanceResults.size())
                .distanceResults(distanceResults)
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
