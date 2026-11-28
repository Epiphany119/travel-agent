package com.travel.module.itinerary.biz.planning.service;

import com.travel.module.itinerary.biz.planning.provider.LocalCityPriceBenchmarkProvider;
import com.travel.module.itinerary.biz.planning.provider.PlaceholderBookingSupplyProvider;
import com.travel.module.itinerary.biz.planning.provider.PlaceholderTransportRoutingProvider;
import com.travel.module.itinerary.biz.planning.provider.TransportRoutingProvider.DayStopsInput;
import com.travel.module.itinerary.biz.planning.provider.TransportRoutingProvider.StopInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommercialPlanningServiceTest {

    private CommercialPlanningService service;

    @BeforeEach
    void setUp() {
        service = new CommercialPlanningService(
                new LocalCityPriceBenchmarkProvider(),
                new PlaceholderTransportRoutingProvider(),
                new PlaceholderBookingSupplyProvider()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void recommendBudgetLinksStartDateSeasonAndSumsSixCategories() {
        Map<String, Object> peak = service.recommendBudget(
                "杭州", "上海", "2026-07-15", 3, 2, "轻松漫游", null, null
        );
        Map<String, Object> offPeak = service.recommendBudget(
                "杭州", "上海", "2026-11-15", 3, 2, "轻松漫游", null, null
        );

        assertEquals("杭州", peak.get("destination"));
        assertEquals("2026-07-15", peak.get("startDate"));
        assertEquals("2026-07-17", peak.get("endDate"));
        assertEquals("旺季", peak.get("seasonLabel"));
        assertEquals("淡季", offPeak.get("seasonLabel"));

        int peakRec = ((Number) peak.get("recommendedBudget")).intValue();
        int offPeakRec = ((Number) offPeak.get("recommendedBudget")).intValue();
        assertTrue(peakRec > offPeakRec);

        List<Map<String, Object>> categories = (List<Map<String, Object>>) peak.get("categories");
        assertEquals(6, categories.size());
        int sum = categories.stream().mapToInt(c -> ((Number) c.get("amount")).intValue()).sum();
        assertEquals(peakRec, sum);
    }

    @Test
    void recommendBudgetEvaluatesAllBudgetHealthStates() {
        Map<String, Object> base = service.recommendBudget("北京", "上海", "2026-05-01", 3, 2, "轻松漫游", null, 5);
        double recommended = ((Number) base.get("recommendedBudget")).doubleValue();

        assertEquals("INSUFFICIENT",
                service.recommendBudget("北京", "上海", "2026-05-01", 3, 2, "轻松漫游", recommended * 0.5, 5).get("budgetHealth"));
        assertEquals("TIGHT",
                service.recommendBudget("北京", "上海", "2026-05-01", 3, 2, "轻松漫游", recommended * 0.85, 5).get("budgetHealth"));
        assertEquals("BALANCED",
                service.recommendBudget("北京", "上海", "2026-05-01", 3, 2, "轻松漫游", recommended * 1.05, 5).get("budgetHealth"));
        assertEquals("AMPLE",
                service.recommendBudget("北京", "上海", "2026-05-01", 3, 2, "轻松漫游", recommended * 1.6, 5).get("budgetHealth"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void calculateDetailedBillLinksSelectedTransportAndHotelTotals() {
        List<Map<String, Object>> activities = List.of(
                Map.of("type", "sightseeing", "name", "西湖游船", "cost", 120),
                Map.of("type", "meal", "name", "楼外楼", "cost", 360),
                Map.of("type", "sightseeing", "name", "断桥残雪")
        );

        Map<String, Object> bill = service.calculateDetailedBill(
                "杭州", "上海", "2026-05-01", 3, 2, "轻松漫游", 3000.0, activities, 960, 1280
        );

        assertEquals(2, bill.get("confirmedActivityCount"));
        assertEquals(1, bill.get("pendingActivityCount"));
        assertEquals("PHASE2_LINKED_BENCHMARK", bill.get("dataSource"));

        List<Map<String, Object>> categories = (List<Map<String, Object>>) bill.get("categories");
        assertEquals(960, findCategoryAmount(categories, "intercity_transport"));
        assertEquals(1280, findCategoryAmount(categories, "hotel"));
        assertEquals(120, findCategoryAmount(categories, "ticket"));
    }

    @Test
    void estimateOptimizeAndBookingPlaceholdersReturnExpectedMetadata() {
        DayStopsInput day = new DayStopsInput(1, List.of(
                new StopInput("故宫", "北京市东城区", "sightseeing"),
                new StopInput("颐和园", "北京市海淀区", "sightseeing"),
                new StopInput("天坛", "北京市东城区", "sightseeing")
        ));

        Map<String, Object> transport = service.estimateTransport("北京", List.of(day));
        assertEquals("PLACEHOLDER", transport.get("dataSource"));

        var opt = service.optimizeDayTransport("北京", day);
        assertEquals(1, opt.day());
        assertEquals("PLACEHOLDER_ROUTING", opt.dataSource());

        var booking = service.getBookingPlaceholders("上海", "杭州", "2026-05-01", 3, 2, "轻松漫游", 4000);
        assertEquals("2026-05-01", booking.startDate());
        assertEquals("2026-05-03", booking.endDate());
        assertEquals("PLACEHOLDER_OTA_SUPPLY", booking.dataSource());
    }

    private int findCategoryAmount(List<Map<String, Object>> categories, String code) {
        return categories.stream()
                .filter(c -> code.equals(c.get("code")))
                .mapToInt(c -> ((Number) c.get("amount")).intValue())
                .findFirst()
                .orElseThrow();
    }
}
