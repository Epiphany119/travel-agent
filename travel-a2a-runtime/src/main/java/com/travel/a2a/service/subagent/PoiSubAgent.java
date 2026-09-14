package com.travel.a2a.service.subagent;

import com.travel.a2a.model.AgentResult;
import com.travel.a2a.model.PoiResult;
import com.travel.a2a.model.TravelPlanRequest;
import com.travel.mcp.client.McpSession;
import com.travel.mcp.protocol.dto.McpToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * POI子Agent
 */
@Slf4j
@Service
public class PoiSubAgent {

    private final McpSession poiSession;

    public PoiSubAgent(@Lazy @Qualifier("poiSession") McpSession poiSession) {
        this.poiSession = poiSession;
    }

    /**
     * 搜索景点
     *
     * @param request 行程请求
     * @return Agent执行结果
     */
    public AgentResult search(TravelPlanRequest request) {
        long startTime = System.currentTimeMillis();
        String destination = request.getDestination();
        List<String> interests = request.getInterests();

        log.info("PoiSubAgent: 搜索POI, destination={}, interests={}", destination, interests);

        try {
            // 搜索真景点：按兴趣挑选高德景点关键词，避开把兴趣词当搜索词（避免搜出餐饮/生活类）
            String keywords = buildAttractionKeywords(interests);
            List<PoiResult> pois = searchOnce(keywords, destination);

            // 兜底：如果第一波只搜到很少景点，再补一次宽泛的「旅游景点」搜索并合并去重
            if (pois.size() < 5 && !keywords.contains("旅游景点")) {
                List<PoiResult> more = searchOnce("旅游景点", destination);
                for (PoiResult p : more) {
                    if (pois.stream().noneMatch(e -> (e.getName() != null && e.getName().equals(p.getName())))) {
                        pois.add(p);
                    }
                }
                log.info("PoiSubAgent: 补搜「旅游景点」后合并, total={}", pois.size());
            }

            long elapsedMs = System.currentTimeMillis() - startTime;
            log.info("PoiSubAgent: 搜索POI成功, count={}, elapsedMs={}", pois.size(), elapsedMs);
            return AgentResult.success("poi", pois, elapsedMs);
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            log.error("PoiSubAgent: 搜索POI异常", e);
            return AgentResult.failure("poi", e.getMessage(), elapsedMs);
        }
    }

    /** 单次高德 POI 景点搜索；失败或异常返回空列表 */
    private List<PoiResult> searchOnce(String keywords, String city) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("keywords", keywords);
            params.put("city", city);
            params.put("offset", 25);
            McpToolResult result = poiSession.callTool("poi.search", params);
            if (result.success()) {
                return parsePoiResults(result.result());
            }
            log.warn("POI 搜索失败: keywords={}, error={}", keywords, result.error());
        } catch (Exception e) {
            log.warn("POI 搜索异常: keywords={}, err={}", keywords, e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * 搜索指定类型的POI
     *
     * @param city     城市
     * @param keywords 关键词
     * @param type     类型（景点/餐饮/购物等）
     * @return Agent执行结果
     */
    public AgentResult searchByType(String city, String keywords, String type) {
        long startTime = System.currentTimeMillis();
        log.info("PoiSubAgent: 按类型搜索POI, city={}, keywords={}, type={}", city, keywords, type);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("keywords", keywords);
            params.put("city", city);
            params.put("type", type);
            params.put("offset", 10);

            McpToolResult result = poiSession.callTool("poi.search", params);

            long elapsedMs = System.currentTimeMillis() - startTime;

            if (result.success()) {
                List<PoiResult> pois = parsePoiResults(result.result());
                return AgentResult.success("poi", pois, elapsedMs);
            } else {
                return AgentResult.failure("poi", result.error(), elapsedMs);
            }
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            log.error("PoiSubAgent: 按类型搜索POI异常", e);
            return AgentResult.failure("poi", e.getMessage(), elapsedMs);
        }
    }

    /**
     * 获取两点间距离
     *
     * @param origin      起点坐标
     * @param destination 终点坐标
     * @return Agent执行结果
     */
    public AgentResult getDistance(String origin, String destination) {
        long startTime = System.currentTimeMillis();
        log.info("PoiSubAgent: 获取距离, origin={}, destination={}", origin, destination);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("origin", origin);
            params.put("destination", destination);

            McpToolResult result = poiSession.callTool("poi.distance", params);

            long elapsedMs = System.currentTimeMillis() - startTime;

            if (result.success()) {
                return AgentResult.success("poi", result.result(), elapsedMs);
            } else {
                return AgentResult.failure("poi", result.error(), elapsedMs);
            }
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            log.error("PoiSubAgent: 获取距离异常", e);
            return AgentResult.failure("poi", e.getMessage(), elapsedMs);
        }
    }

    /**
     * 构建搜索关键词
     */
    private String buildAttractionKeywords(List<String> interests) {
        // 按兴趣选择高德「景点」关键词，保证搜到真景点，让计划里景点更多、更丰富
        List<String> flat = new ArrayList<>();
        if (interests != null) {
            for (String item : interests) {
                if (item == null) continue;
                for (String part : item.split("[,，\\s]+")) {
                    String t = part.trim();
                    if (!t.isEmpty()) flat.add(t);
                }
            }
        }
        if (flat.stream().anyMatch(i -> i.contains("自然"))) return "公园 景区 自然风景";
        if (flat.stream().anyMatch(i -> i.contains("摄影") || i.contains("网红"))) return "景点 景区";
        // 默认 & 人文/历史：用宽泛词保证景点数量（过窄关键词会搜到个位数）
        return "旅游景点 博物馆 古迹";
    }

    @SuppressWarnings("unchecked")
    private List<PoiResult> parsePoiResults(Object raw) {
        List<PoiResult> pois = new ArrayList<>();

        Object poisObj = extractField(raw, "pois");
        if (poisObj == null) {
            poisObj = extractListRecursive(raw);
        }
        if (poisObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    pois.add(PoiResult.builder()
                            .placeId(normalizePlaceId("poi", map.get("placeId") != null
                                    ? map.get("placeId") : map.get("id")))
                            .name(getStr(map, "name"))
                            .address(getStr(map, "address"))
                            .type(getStr(map, "type"))
                            .distance(getInt(map, "distance"))
                            .tel(getStr(map, "tel"))
                            .longitude(getDbl(map, "longitude"))
                            .latitude(getDbl(map, "latitude"))
                            .build());
                }
            }
        }
        return pois;
    }

    private static String normalizePlaceId(String source, Object value) {
        if (value == null || value.toString().isBlank()) return null;
        String id = value.toString().trim();
        return id.startsWith(source + ":") ? id : source + ":" + id;
    }

    @SuppressWarnings("unchecked")
    private Object extractField(Object obj, String field) {
        if (!(obj instanceof Map<?, ?> m)) return null;
        Object val = m.get(field);
        if (val != null) return val;
        Object inner = m.get("result");
        if (inner instanceof Map<?, ?> m2) {
            val = m2.get(field);
            if (val instanceof List<?>) return val;
            Object inner2 = m2.get("result");
            if (inner2 instanceof Map<?, ?> m3) {
                val = m3.get(field);
                if (val instanceof List<?>) return val;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<?> extractListRecursive(Object obj) {
        if (obj instanceof List<?> l && !l.isEmpty()) return l;
        if (obj instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                List<?> found = extractListRecursive(v);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String getStr(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v != null ? v.toString() : null;
    }

    private static Integer getInt(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Double getDbl(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : null;
    }
}
