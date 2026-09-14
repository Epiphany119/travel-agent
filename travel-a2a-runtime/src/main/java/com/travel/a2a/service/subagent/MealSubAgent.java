package com.travel.a2a.service.subagent;

import com.travel.a2a.model.AgentResult;
import com.travel.a2a.model.MealResult;
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
 * 餐饮子Agent
 */
@Slf4j
@Service
public class MealSubAgent {

    private final McpSession mealSession;

    public MealSubAgent(@Lazy @Qualifier("mealSession") McpSession mealSession) {
        this.mealSession = mealSession;
    }

    /**
     * 搜索餐厅
     *
     * @param request 行程请求
     * @return Agent执行结果
     */
    public AgentResult search(TravelPlanRequest request) {
        long startTime = System.currentTimeMillis();
        String destination = request.getDestination();
        String travelStyle = request.getTravelStyle();

        log.info("MealSubAgent: 搜索餐厅, destination={}, travelStyle={}", destination, travelStyle);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("city", destination);
            params.put("keywords", buildKeywords(travelStyle));
            params.put("offset", 15);

            McpToolResult result = mealSession.callTool("meal.search", params);

            long elapsedMs = System.currentTimeMillis() - startTime;

            if (result.success()) {
                List<MealResult> meals = parseMealResults(result.result());
                log.info("MealSubAgent: 搜索餐厅成功, count={}, elapsedMs={}", meals.size(), elapsedMs);
                return AgentResult.success("meal", meals, elapsedMs);
            } else {
                log.warn("MealSubAgent: 搜索餐厅失败, error={}", result.error());
                return AgentResult.failure("meal", result.error(), elapsedMs);
            }
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            log.error("MealSubAgent: 搜索餐厅异常", e);
            return AgentResult.failure("meal", e.getMessage(), elapsedMs);
        }
    }

    /**
     * 搜索指定类型的餐厅
     *
     * @param city   城市
     * @param cuisine 菜系类型
     * @return Agent执行结果
     */
    public AgentResult searchByCuisine(String city, String cuisine) {
        long startTime = System.currentTimeMillis();
        log.info("MealSubAgent: 按菜系搜索餐厅, city={}, cuisine={}", city, cuisine);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("city", city);
            params.put("cuisine", cuisine);
            params.put("offset", 10);

            McpToolResult result = mealSession.callTool("meal.search", params);

            long elapsedMs = System.currentTimeMillis() - startTime;

            if (result.success()) {
                List<MealResult> meals = parseMealResults(result.result());
                return AgentResult.success("meal", meals, elapsedMs);
            } else {
                return AgentResult.failure("meal", result.error(), elapsedMs);
            }
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            log.error("MealSubAgent: 按菜系搜索餐厅异常", e);
            return AgentResult.failure("meal", e.getMessage(), elapsedMs);
        }
    }

    /**
     * 获取餐厅详情
     *
     * @param restaurantId 餐厅ID
     * @return Agent执行结果
     */
    public AgentResult getDetail(String restaurantId) {
        long startTime = System.currentTimeMillis();
        log.info("MealSubAgent: 获取餐厅详情, restaurantId={}", restaurantId);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", restaurantId);

            McpToolResult result = mealSession.callTool("meal.get_detail", params);

            long elapsedMs = System.currentTimeMillis() - startTime;

            if (result.success()) {
                return AgentResult.success("meal", result.result(), elapsedMs);
            } else {
                return AgentResult.failure("meal", result.error(), elapsedMs);
            }
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            log.error("MealSubAgent: 获取餐厅详情异常", e);
            return AgentResult.failure("meal", e.getMessage(), elapsedMs);
        }
    }

    /**
     * 构建搜索关键词
     */
    private String buildKeywords(String travelStyle) {
        if (travelStyle == null || travelStyle.isEmpty()) {
            return "特色餐厅";
        }
        return travelStyle + " 美食";
    }

    /**
     * 解析餐饮结果
     */
    @SuppressWarnings("unchecked")
    private List<MealResult> parseMealResults(Object raw) {
        List<MealResult> meals = new ArrayList<>();

        // 优先精确提取
        Object mealsObj = extractField(raw, "pois");
        if (mealsObj == null) {
            mealsObj = extractListRecursive(raw);
        }
        if (mealsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    MealResult meal = MealResult.builder()
                            .placeId(normalizePlaceId("meal", map.get("placeId") != null
                                    ? map.get("placeId") : map.get("id")))
                            .name(getStr(map, "name"))
                            .address(getStr(map, "address"))
                            .cuisine(getStr(map, "cuisine"))
                            .avgPrice(getInt(map, "avgPrice"))
                            .rating(getDbl(map, "rating"))
                            .build();
                    meals.add(meal);
                }
            }
        }

        return meals;
    }

    private static String normalizePlaceId(String source, Object value) {
        if (value == null || value.toString().isBlank()) return null;
        String id = value.toString().trim();
        return id.startsWith(source + ":") ? id : source + ":" + id;
    }

    /** 安全提取嵌套字段，支持双重嵌套结构 */
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

    /** 通用递归提取 List——遍历所有 Map 键值，找第一个含 poi 数据的 List */
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
