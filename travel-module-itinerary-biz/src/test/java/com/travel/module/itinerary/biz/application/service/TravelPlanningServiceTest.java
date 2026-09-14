package com.travel.module.itinerary.biz.application.service;

import com.travel.common.tool.PoiTool;
import com.travel.common.tool.WeatherTool;
import com.travel.module.itinerary.biz.api.dto.GenerateItineraryRequest;
import com.travel.module.itinerary.biz.api.dto.TravelPlanResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Guards the provider-response-to-UI contract: provider facts must not be replaced by templates. */
class TravelPlanningServiceTest {

    @Test
    void shouldMapLivePoiAndWeatherResponsesIntoPlan() {
        WeatherTool weatherTool = mock(WeatherTool.class);
        PoiTool poiTool = mock(PoiTool.class);
        when(weatherTool.apply(any())).thenReturn(WeatherTool.WeatherResponse.builder().success(true).city("杭州")
                .weatherList(List.of(WeatherTool.DailyWeather.builder().date("2026-08-12").textDay("小雨")
                        .tempMin(24).tempMax(30).precip(1.2).uvIndex(3).build())).build());
        when(poiTool.apply(any())).thenReturn(PoiTool.AmapResponse.builder().success(true).count(2).pois(List.of(
                PoiTool.PoiInfo.builder().name("西湖风景名胜区").address("西湖区龙井路1号").adname("西湖区").type("风景名胜").build(),
                PoiTool.PoiInfo.builder().name("知味观").address("上城区仁和路83号").adname("上城区").type("餐饮服务").build())).build());
        TravelPlanningService service = new TravelPlanningService(weatherTool, poiTool);

        GenerateItineraryRequest request = new GenerateItineraryRequest();
        request.setDestination("杭州"); request.setDays(1); request.setBudget(3000D); request.setTravelStyle("轻松漫游");
        request.setInterests(List.of("美食"));
        TravelPlanResponse plan = service.generate(request);

        assertThat(plan.getDayPlans().get(0).getAttractions()).extracting("name").contains("西湖风景名胜区", "知味观");
        assertThat(plan.getDayPlans().get(0).getMeals()).extracting("restaurantName").contains("西湖风景名胜区", "知味观");
        assertThat(plan.getTravelTips()).anyMatch(tip -> tip.contains("小雨")).anyMatch(tip -> tip.contains("雨具"));
        assertThat(plan.getOverview()).contains("高德地图获取 2 个景点候选");
        assertThat(plan.getEstimatedCost()).isNull();
        assertThat(plan.getDayPlans().get(0).getAttractions()).extracting("name").doesNotContain("杭州城市地标 / 核心景区");
        ArgumentCaptor<PoiTool.AmapRequest> poiRequest = ArgumentCaptor.forClass(PoiTool.AmapRequest.class);
        verify(poiTool, times(2)).apply(poiRequest.capture());
        assertThat(poiRequest.getAllValues()).allMatch(value -> "poi".equals(value.getAction()));
    }

    @Test
    void shouldExposeProviderFailureInsteadOfFakingRecommendations() {
        WeatherTool weatherTool = mock(WeatherTool.class);
        PoiTool poiTool = mock(PoiTool.class);
        when(weatherTool.apply(any())).thenReturn(WeatherTool.WeatherResponse.fallback("API 密钥未配置"));
        when(poiTool.apply(any())).thenReturn(PoiTool.AmapResponse.fallback("高德地图服务已禁用"));
        TravelPlanningService service = new TravelPlanningService(weatherTool, poiTool);

        GenerateItineraryRequest request = new GenerateItineraryRequest();
        request.setDestination("杭州"); request.setDays(1); request.setBudget(3000D);
        TravelPlanResponse plan = service.generate(request);

        assertThat(plan.getDayPlans().get(0).getAttractions()).isEmpty();
        assertThat(plan.getDayPlans().get(0).getMeals()).isEmpty();
        assertThat(plan.getDataWarnings()).isNotEmpty().anyMatch(warning -> warning.contains("高德地图"));
        assertThat(plan.getEstimatedCost()).isNull();
    }
}
