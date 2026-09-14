package com.travel.module.itinerary.biz.application.service;

import com.travel.common.tool.PoiTool;
import com.travel.common.tool.WeatherTool;
import com.travel.module.itinerary.biz.api.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Builds the UI contract from real provider responses. No attraction, restaurant, rating or
 * opening-hour value is invented: unavailable provider data is returned as an explicit warning.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TravelPlanningService {
    private final WeatherTool weatherTool;
    private final PoiTool poiTool;

    public TravelPlanResponse generate(GenerateItineraryRequest request) {
        String city = request.getDestination().trim();
        List<String> warnings = new ArrayList<>();
        WeatherTool.WeatherResponse weather = queryWeather(city, warnings);
        // 景点和餐饮必须来自真实 POI 候选；查询失败时由 queryPoi 返回空列表并记录告警，
        // 不再静默关闭 POI 让上层误以为“没有推荐结果”。
        List<PoiTool.PoiInfo> attractions = queryPoi(city, attractionKeyword(request), warnings);
        List<PoiTool.PoiInfo> restaurants = queryPoi(city, restaurantKeyword(request), warnings);
        List<DayPlanResponse> dayPlans = buildDays(request, attractions, restaurants, weather);
        Double estimatedCost = estimateCost();

        return TravelPlanResponse.builder()
                .planId("TP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .destination(city)
                .days(request.getDays())
                .totalBudget(request.getBudget())
                .estimatedCost(estimatedCost)
                .budgetStatus("预算由你输入；餐饮与门票价格需以地点官方信息为准")
                .overview(buildOverview(city, attractions, restaurants, weather))
                .dayPlans(dayPlans)
                .travelTips(buildTips(weather, warnings))
                .packingList(buildPackingList(weather))
                .dataSources(List.of("高德地图 POI", "和风天气"))
                .dataWarnings(warnings)
                .build();
    }

    private WeatherTool.WeatherResponse queryWeather(String city, List<String> warnings) {
        WeatherTool.WeatherRequest request = new WeatherTool.WeatherRequest();
        request.setCity(city);
        request.setType("7d");
        WeatherTool.WeatherResponse response = weatherTool.apply(request);
        if (!response.isSuccess()) warnings.add("和风天气未返回数据：" + safe(response.getMessage()));
        return response;
    }

    private List<PoiTool.PoiInfo> queryPoi(String city, String keyword, List<String> warnings) {
        PoiTool.AmapRequest request = new PoiTool.AmapRequest();
        request.setAction("poi");
        request.setCity(city);
        request.setCitylimit(true);
        request.setKeywords(keyword);
        request.setOffset(20);
        PoiTool.AmapResponse response = poiTool.apply(request);
        if (!response.isSuccess()) {
            warnings.add("高德地图「" + keyword + "」查询失败：" + safe(response.getMessage()));
            return Collections.emptyList();
        }
        if (response.getPois() == null || response.getPois().isEmpty()) {
            warnings.add("高德地图未找到「" + keyword + "」相关地点");
            return Collections.emptyList();
        }
        return response.getPois();
    }

    private List<DayPlanResponse> buildDays(GenerateItineraryRequest request, List<PoiTool.PoiInfo> attractions,
                                            List<PoiTool.PoiInfo> restaurants, WeatherTool.WeatherResponse weather) {
        List<DayPlanResponse> days = new ArrayList<>();
        double perDay = Math.floor(request.getBudget() / request.getDays());
        for (int day = 0; day < request.getDays(); day++) {
            List<AttractionResponse> points = selectAttractions(attractions, day);
            List<MealResponse> meals = selectMeals(restaurants, day);
            WeatherTool.DailyWeather forecast = forecastFor(weather, day);
            days.add(DayPlanResponse.builder()
                    .dayNumber(day + 1)
                    .date(forecast != null && forecast.getDate() != null ? forecast.getDate() : "第 " + (day + 1) + " 天")
                    .theme(buildTheme(request.getTravelStyle(), forecast))
                    .attractions(points)
                    .meals(meals)
                    .transportation(points.size() > 1 ? "地点坐标来自高德地图；请在出行当日使用高德地图确认实时路线与拥堵情况。" : "暂无足够 POI 数据生成同日路线。")
                    .notes(weatherNote(forecast))
                    .dayBudget(perDay)
                    .build());
        }
        return days;
    }

    private List<AttractionResponse> selectAttractions(List<PoiTool.PoiInfo> pois, int day) {
        if (pois.isEmpty()) return Collections.emptyList();
        List<AttractionResponse> result = new ArrayList<>();
        int count = Math.min(2, pois.size());
        for (int offset = 0; offset < count; offset++) {
            PoiTool.PoiInfo poi = pois.get((day * 2 + offset) % pois.size());
            result.add(AttractionResponse.builder().name(poi.getName()).description("高德地图 POI：" + safe(poi.getType()))
                    .duration(null).ticketPrice(null).openTime("请以地点官方公告为准")
                    .address(composeAddress(poi)).rating(null).visitOrder(offset + 1).build());
        }
        return result;
    }

    private List<MealResponse> selectMeals(List<PoiTool.PoiInfo> pois, int day) {
        if (pois.isEmpty()) return Collections.emptyList();
        List<MealResponse> result = new ArrayList<>();
        String[] mealTypes = {"午餐", "晚餐"};
        for (int offset = 0; offset < Math.min(2, pois.size()); offset++) {
            PoiTool.PoiInfo poi = pois.get((day * 2 + offset) % pois.size());
            result.add(MealResponse.builder().mealType(mealTypes[offset]).restaurantName(poi.getName())
                    .cuisine(safe(poi.getType())).avgPrice(null).address(composeAddress(poi))
                    .reason("来自高德地图 POI 查询结果；请出发前确认营业时间、价格与排队情况。")
                    .rating(null).build());
        }
        return result;
    }

    private String attractionKeyword(GenerateItineraryRequest request) {
        if (request.getInterests() != null && request.getInterests().contains("自然")) return "公园 景区";
        if (request.getInterests() != null && request.getInterests().contains("人文")) return "博物馆 古迹";
        return "旅游景点";
    }

    private String restaurantKeyword(GenerateItineraryRequest request) {
        return request.getInterests() != null && request.getInterests().contains("美食") ? "当地美食" : "餐厅";
    }

    private String buildOverview(String city, List<PoiTool.PoiInfo> attractions, List<PoiTool.PoiInfo> restaurants,
                                 WeatherTool.WeatherResponse weather) {
        return city + "：已从高德地图获取 " + attractions.size() + " 个景点候选和 " + restaurants.size()
                + " 个餐饮候选" + (weather.isSuccess() ? "，并结合和风天气预报安排每日提醒。" : "；天气数据暂不可用，出发前请再次查询。" );
    }

    private List<String> buildTips(WeatherTool.WeatherResponse weather, List<String> warnings) {
        List<String> tips = new ArrayList<>();
        if (weather.isSuccess() && weather.getWeatherList() != null) {
            weather.getWeatherList().stream().limit(7).forEach(day -> tips.add(day.getDate() + "：" + safe(day.getTextDay())
                    + "，" + day.getTempMin() + "–" + day.getTempMax() + "℃，" + weatherAdvice(day)));
        }
        if (tips.isEmpty()) tips.add("天气服务暂不可用，请在出发前通过官方天气渠道复核。");
        tips.addAll(warnings);
        return tips;
    }

    private List<String> buildPackingList(WeatherTool.WeatherResponse weather) {
        List<String> list = new ArrayList<>(List.of("身份证件与充电宝", "舒适步行鞋"));
        if (weather.isSuccess() && weather.getWeatherList() != null && weather.getWeatherList().stream()
                .anyMatch(day -> day.getPrecip() != null && day.getPrecip() > 0)) list.add("雨具");
        if (weather.isSuccess() && weather.getWeatherList() != null && weather.getWeatherList().stream()
                .anyMatch(day -> day.getUvIndex() != null && day.getUvIndex() >= 5)) list.add("防晒用品");
        return list;
    }

    private WeatherTool.DailyWeather forecastFor(WeatherTool.WeatherResponse weather, int day) {
        if (!weather.isSuccess() || weather.getWeatherList() == null || weather.getWeatherList().isEmpty()) return null;
        return weather.getWeatherList().get(Math.min(day, weather.getWeatherList().size() - 1));
    }

    private String buildTheme(String style, WeatherTool.DailyWeather forecast) {
        return style + (forecast == null ? " · 等待天气数据" : " · " + safe(forecast.getTextDay()));
    }

    private String weatherNote(WeatherTool.DailyWeather day) {
        return day == null ? "未接入到当日天气数据，请在出发前复核。" : "和风天气：" + safe(day.getTextDay()) + "，"
                + day.getTempMin() + "–" + day.getTempMax() + "℃。" + weatherAdvice(day);
    }

    private String weatherAdvice(WeatherTool.DailyWeather day) {
        if (day.getPrecip() != null && day.getPrecip() > 0) return "建议携带雨具。";
        if (day.getUvIndex() != null && day.getUvIndex() >= 5) return "紫外线较强，注意防晒。";
        return "请按实时体感调整衣物。";
    }

    private Double estimateCost() {
        // Neither provider returns reliable ticket/restaurant prices; null means unknown.
        // Zero would incorrectly communicate that the itinerary is free.
        return null;
    }

    private String composeAddress(PoiTool.PoiInfo poi) {
        String prefix = poi.getAdname() == null ? "" : poi.getAdname();
        String address = poi.getAddress() == null ? "地址请在高德地图详情确认" : poi.getAddress();
        return prefix + address;
    }

    private String safe(String value) { return value == null || value.isBlank() ? "未提供" : value; }
}
