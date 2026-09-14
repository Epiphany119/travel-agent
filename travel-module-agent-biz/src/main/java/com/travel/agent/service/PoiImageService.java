package com.travel.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.common.http.HttpClientSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 地点/餐厅图片服务。
 *
 * <p>复用已有高德 key（gaode.api-key），用：
 *   1. place/text 按名称搜出 POI 的 id；
 *   2. place/detail 按 id 取出该地点的官方照片。
 * 全程容错：异常/无数据都返回空列表，绝不抛出。</p>
 */
@Slf4j
@Service
public class PoiImageService {

    private static final String TEXT_SEARCH_URL = "https://restapi.amap.com/v3/place/text";
    private static final String DETAIL_URL = "https://restapi.amap.com/v3/place/detail";
    private static final int MAX_IMAGES = 3;

    private final RestTemplate restTemplate = HttpClientSupport.newRestTemplate();

    @Value("${gaode.api-key:}")
    private String apiKey;

    public List<String> fetchImages(String name, String city) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("高德 api-key 未配置，无法获取地点图片");
            return List.of();
        }
        if (name == null || name.isBlank()) {
            return List.of();
        }
        try {
            // 不只取第一个 POI：同名地点第一个结果可能没有照片，扩大候选能显著降低空图率。
            List<JsonNode> pois = searchPois(name, city);
            if (pois.isEmpty()) {
                log.debug("未找到 POI: name={}, city={}", name, safe(city));
                return List.of();
            }

            List<String> photos = new ArrayList<>();
            for (JsonNode poi : pois) {
                appendPhotos(photos, collectPhotos(poi.path("photos")));
                if (photos.size() >= MAX_IMAGES) return photos.subList(0, MAX_IMAGES);
            }

            // 文本搜索没图时，再按多个候选 POI 的 id 查询详情，避免单个结果失效导致整组空图。
            for (JsonNode poi : pois) {
                String poiId = poi.path("id").asText(null);
                if (poiId == null || poiId.isBlank()) continue;
                appendPhotos(photos, fetchDetailPhotos(poiId));
                if (photos.size() >= MAX_IMAGES) return photos.subList(0, MAX_IMAGES);
            }
            return photos;
        } catch (Exception e) {
            log.warn("获取地点图片失败: name={}, err={}", name, e.getMessage());
            return List.of();
        }
    }

    private String searchPoiId(String keywords, String city) {
        JsonNode poi = searchFirst(keywords, city);
        if (poi == null) return null;
        String id = poi.path("id").asText(null);
        return (id == null || id.isBlank()) ? null : id;
    }

    /** 文本搜索取第一个 POI 自带 photos（最多 3 张） */
    private List<String> searchPhotos(String keywords, String city) {
        JsonNode poi = searchFirst(keywords, city);
        if (poi == null) return List.of();
        return collectPhotos(poi.path("photos"));
    }

    private JsonNode searchFirst(String keywords, String city) {
        String url = UriComponentsBuilder.fromHttpUrl(TEXT_SEARCH_URL)
                .queryParam("key", apiKey)
                .queryParam("keywords", keywords)
                .queryParam("city", safe(city))
                .queryParam("citylimit", true)
                .queryParam("offset", 1)
                .queryParam("page", 1)
                .build().toUriString();

        JsonNode root = restTemplate.getForObject(url, JsonNode.class);
        if (root == null || !"1".equals(root.path("status").asText())) {
            log.debug("高德文本搜索失败: name={}", keywords);
            return null;
        }
        JsonNode pois = root.path("pois");
        if (pois == null || !pois.isArray() || pois.isEmpty()) {
            return null;
        }
        return pois.get(0);
    }

    private List<String> fetchDetailPhotos(String poiId) {
        String url = UriComponentsBuilder.fromHttpUrl(DETAIL_URL)
                .queryParam("key", apiKey)
                .queryParam("id", poiId)
                .build().toUriString();

        JsonNode root = restTemplate.getForObject(url, JsonNode.class);
        if (root == null || !"1".equals(root.path("status").asText())) {
            log.debug("高德详情接口失败: poiId={}", poiId);
            return List.of();
        }
        JsonNode pois = root.path("pois");
        if (pois == null || !pois.isArray() || pois.isEmpty()) {
            return List.of();
        }
        return collectPhotos(pois.get(0).path("photos"));
    }

    /** 从高德 photos 节点提取 url，去重、最多 MAX_IMAGES 张 */
    private List<String> collectPhotos(JsonNode photos) {
        List<String> urls = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (photos != null && photos.isArray()) {
            for (JsonNode p : photos) {
                String u = p.path("url").asText(null);
                if (u != null && !u.isBlank() && seen.add(u)) {
                    urls.add(u);
                    if (urls.size() >= MAX_IMAGES) break;
                }
            }
        }
        return urls;
    }

    private void appendPhotos(List<String> target, List<String> candidates) {
        for (String url : candidates) {
            if (url != null && !url.isBlank() && !target.contains(url)) {
                target.add(url);
                if (target.size() >= MAX_IMAGES) return;
            }
        }
    }

    private List<JsonNode> searchPois(String keywords, String city) {
        String url = UriComponentsBuilder.fromHttpUrl(TEXT_SEARCH_URL)
                .queryParam("key", apiKey)
                .queryParam("keywords", keywords)
                .queryParam("city", safe(city))
                .queryParam("citylimit", true)
                .queryParam("offset", 5)
                .queryParam("page", 1)
                .build().toUriString();

        JsonNode root = restTemplate.getForObject(url, JsonNode.class);
        if (root == null || !"1".equals(root.path("status").asText())) return List.of();
        JsonNode pois = root.path("pois");
        if (pois == null || !pois.isArray() || pois.isEmpty()) return List.of();

        List<JsonNode> result = new ArrayList<>();
        for (JsonNode poi : pois) result.add(poi);
        return result;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
