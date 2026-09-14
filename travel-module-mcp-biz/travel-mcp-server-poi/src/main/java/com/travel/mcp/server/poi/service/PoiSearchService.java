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
public class PoiSearchService {

    private static final String POI_SEARCH_URL = "https://restapi.amap.com/v3/place/text";
    private static final String INPUTTIPS_URL = "https://restapi.amap.com/v3/assistant/inputtips";

    private final RestTemplate restTemplate;

    @Value("${travel.poi.amap-key:}")
    private String apiKey;

    public PoiSearchService(@Qualifier("poiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public AmapResponse search(String keywords, String city, String types, Integer limit) {
        if (apiKey == null || apiKey.isBlank()) {
            return AmapResponse.fallback("高德地图API密钥未配置");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(POI_SEARCH_URL)
                .queryParam("key", apiKey)
                .queryParam("keywords", keywords)
                .queryParam("types", types)
                .queryParam("city", city)
                .queryParam("citylimit", true)
                .queryParam("offset", limit != null ? limit : 20)
                .queryParam("page", 1);

        String url = builder.build().toUriString();

        log.debug("POI搜索请求: keywords={}, city={}", keywords, city);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            return parsePoiSearchResponse(response);
        } catch (Exception e) {
            log.error("POI搜索API调用异常", e);
            return AmapResponse.fallback("API调用异常: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private AmapResponse parsePoiSearchResponse(Map<String, Object> response) {
        if (response == null) {
            return AmapResponse.fallback("POI搜索API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback("POI搜索失败: " + info);
        }

        List<Map<String, Object>> pois = (List<Map<String, Object>>) response.get("pois");
        if (pois == null || pois.isEmpty()) {
            return AmapResponse.builder()
                    .success(true)
                    .action("poi")
                    .count(0)
                    .pois(new ArrayList<>())
                    .message("未找到相关POI")
                    .build();
        }

        List<AmapResponse.PoiInfo> poiList = pois.stream()
                .map(this::parsePoiInfo)
                .toList();

        return AmapResponse.builder()
                .success(true)
                .action("poi")
                .count(poiList.size())
                .total(parseInt(response.get("count")))
                .pois(poiList)
                .build();
    }

    private AmapResponse.PoiInfo parsePoiInfo(Map<String, Object> poi) {
        return AmapResponse.PoiInfo.builder()
                .id((String) poi.get("id"))
                .name((String) poi.get("name"))
                .location((String) poi.get("location"))
                .address((String) poi.get("address"))
                .type((String) poi.get("type"))
                .typecode((String) poi.get("typecode"))
                .tel(toString(poi.get("tel")))
                .pname((String) poi.get("pname"))
                .cityname((String) poi.get("cityname"))
                .adname((String) poi.get("adname"))
                .build();
    }

    private String toString(Object value) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        if (value instanceof List) return String.join(", ", ((List<?>) value).stream().map(Object::toString).toList());
        return value.toString();
    }

    public AmapResponse inputtips(String keywords, String city, String location) {
        if (apiKey == null || apiKey.isBlank()) {
            return AmapResponse.fallback("高德地图API密钥未配置");
        }

        if (keywords == null || keywords.isBlank()) {
            return AmapResponse.fallback("输入提示需要提供关键词(keywords)");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(INPUTTIPS_URL)
                .queryParam("key", apiKey)
                .queryParam("keywords", keywords)
                .queryParam("city", city)
                .queryParam("datatype", "poi")
                .queryParam("location", location);

        String url = builder.build().toUriString();

        log.debug("输入提示请求: keywords={}, city={}", keywords, city);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            return parseInputtipsResponse(response);
        } catch (Exception e) {
            log.error("输入提示API调用异常", e);
            return AmapResponse.fallback("API调用异常: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private AmapResponse parseInputtipsResponse(Map<String, Object> response) {
        if (response == null) {
            return AmapResponse.fallback("输入提示API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback("输入提示失败: " + info);
        }

        List<Map<String, Object>> tips = (List<Map<String, Object>>) response.get("tips");
        if (tips == null || tips.isEmpty()) {
            return AmapResponse.builder()
                    .success(true)
                    .action("inputtips")
                    .count(0)
                    .inputtips(new ArrayList<>())
                    .message("未找到相关提示")
                    .build();
        }

        List<AmapResponse.InputTip> inputTips = tips.stream()
                .map(this::parseInputTip)
                .toList();

        return AmapResponse.builder()
                .success(true)
                .action("inputtips")
                .count(inputTips.size())
                .inputtips(inputTips)
                .build();
    }

    private AmapResponse.InputTip parseInputTip(Map<String, Object> tip) {
        return AmapResponse.InputTip.builder()
                .id((String) tip.get("id"))
                .name((String) tip.get("name"))
                .district((String) tip.get("district"))
                .adcode((String) tip.get("adcode"))
                .location((String) tip.get("location"))
                .address((String) tip.get("address"))
                .typecode((String) tip.get("typecode"))
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
