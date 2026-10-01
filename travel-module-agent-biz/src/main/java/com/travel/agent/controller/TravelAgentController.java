package com.travel.agent.controller;

import com.travel.agent.service.TravelPlanningService;
import com.travel.common.tool.PoiTool;
import com.travel.common.tool.WeatherTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 旅行规划控制器
 * 提供API接口用于测试和调用
 */
@Slf4j
@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelAgentController {

    private final TravelPlanningService travelPlanningService;

    /**
     * 生成旅行行程
     */
    @PostMapping("/plan")
    public Map<String, Object> generateItinerary(@RequestBody Map<String, String> request) {
        String userRequest = request.get("request");
        if (userRequest == null || userRequest.isBlank()) {
            return Map.of("success", false, "message", "请求内容不能为空");
        }
        userRequest = userRequest.trim();
        if (userRequest.length() > 4000) {
            return Map.of("success", false, "message", "请求内容不能超过 4000 个字符");
        }

        String result = travelPlanningService.generateItinerary(userRequest);
        return Map.of("success", true, "verified", false,
                "dataWarning", "此兼容接口返回模型文本，未接入结构化计划校验；请使用 /a2a/tasks 或 /api/travel-plans/generate 获取可验证计划",
                "data", result);
    }

    /**
     * 查询天气
     */
    @GetMapping("/weather")
    public WeatherTool.WeatherResponse getWeather(@RequestParam String city) {
        return travelPlanningService.getWeather(city);
    }

    /**
     * 搜索POI
     */
    @GetMapping("/poi")
    public PoiTool.AmapResponse searchPOI(
            @RequestParam String keywords,
            @RequestParam(required = false) String city) {
        return travelPlanningService.searchPOI(keywords, city);
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "travel-agent");
    }
}
