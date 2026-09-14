package com.travel.mcp.server.poi.service;

import com.travel.mcp.server.poi.model.AmapResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Slf4j
@Service
public class GeocodeService {

    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String REGEO_URL = "https://restapi.amap.com/v3/geocode/regeo";

    private final RestTemplate restTemplate;

    @Value("${travel.poi.amap-key:}")
    private String apiKey;

    public GeocodeService(@Qualifier("poiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public AmapResponse geocode(String address, String city) {
        if (apiKey == null || apiKey.isBlank()) {
            return AmapResponse.fallback("高德地图API密钥未配置");
        }

        String url = UriComponentsBuilder.fromHttpUrl(GEOCODE_URL)
                .queryParam("key", apiKey)
                .queryParam("address", address)
                .queryParam("city", city)
                .build()
                .toUriString();

        log.debug("地理编码请求: {}", address);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            return parseGeocodeResponse(response);
        } catch (Exception e) {
            log.error("地理编码API调用异常", e);
            return AmapResponse.fallback("API调用异常: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private AmapResponse parseGeocodeResponse(Map<String, Object> response) {
        if (response == null) {
            return AmapResponse.fallback("地理编码API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback("地理编码失败: " + info);
        }

        var geocodes = (java.util.List<Map<String, Object>>) response.get("geocodes");
        if (geocodes == null || geocodes.isEmpty()) {
            return AmapResponse.builder()
                    .success(true)
                    .action("geocode")
                    .message("未找到该地址的坐标")
                    .build();
        }

        Map<String, Object> firstResult = geocodes.get(0);
        AmapResponse.GeocodeResult result = AmapResponse.GeocodeResult.builder()
                .location((String) firstResult.get("location"))
                .province((String) firstResult.get("province"))
                .city((String) firstResult.get("city"))
                .district((String) firstResult.get("district"))
                .adcode((String) firstResult.get("adcode"))
                .build();

        return AmapResponse.builder()
                .success(true)
                .action("geocode")
                .count(1)
                .geocodeResult(result)
                .build();
    }

    public AmapResponse regeo(String location, String extensions) {
        if (apiKey == null || apiKey.isBlank()) {
            return AmapResponse.fallback("高德地图API密钥未配置");
        }

        String url = UriComponentsBuilder.fromHttpUrl(REGEO_URL)
                .queryParam("key", apiKey)
                .queryParam("location", location)
                .queryParam("extensions", extensions != null ? extensions : "all")
                .build()
                .toUriString();

        log.debug("逆地理编码请求: {}", location);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            return parseRegeoResponse(response);
        } catch (Exception e) {
            log.error("逆地理编码API调用异常", e);
            return AmapResponse.fallback("API调用异常: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private AmapResponse parseRegeoResponse(Map<String, Object> response) {
        if (response == null) {
            return AmapResponse.fallback("逆地理编码API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback("逆地理编码失败: " + info);
        }

        Map<String, Object> regeo = (Map<String, Object>) response.get("regeocode");
        if (regeo == null) {
            return AmapResponse.fallback("未找到该坐标对应的地址");
        }

        Map<String, Object> addressComponent = (Map<String, Object>) regeo.get("addressComponent");
        AmapResponse.RegeoResult result = AmapResponse.RegeoResult.builder()
                .formattedAddress((String) regeo.get("formatted_address"))
                .province(addressComponent != null ? (String) addressComponent.get("province") : null)
                .city(addressComponent != null ? (String) addressComponent.get("city") : null)
                .district(addressComponent != null ? (String) addressComponent.get("district") : null)
                .adcode(addressComponent != null ? (String) addressComponent.get("adcode") : null)
                .township(addressComponent != null ? (String) addressComponent.get("township") : null)
                .streetNumber(regeo.get("streetNumber") != null ?
                        (String) ((Map<String, Object>) regeo.get("streetNumber")).get("street") : null)
                .build();

        return AmapResponse.builder()
                .success(true)
                .action("regeo")
                .count(1)
                .regeoResult(result)
                .build();
    }
}
