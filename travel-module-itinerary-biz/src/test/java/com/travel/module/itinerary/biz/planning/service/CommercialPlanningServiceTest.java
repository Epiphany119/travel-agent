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
    void recommendBudgetBreaksDownSixCategoriesAndSumsToRecommendedBudget() {
        Map<String, Object> result = service.recommendBudget(
                "杭州", "上海", 3, 2, "轻松漫游", null, 5
        );

        assertEquals("杭州", result.get("destination"));
        assertEquals("TIER_2", result.get("cityTier"));
        assertEquals("CITY_BENCHMARK_PLACEHOLDER", result.get("dataSource"));

        int minBudget = ((Number) result.get("minBudget")).intValue();
        int recommended = ((Number) result.get("recommendedBudget")).intValue();
        int maxBudget = ((Number) result.get("maxBudget")).intValue();
        assertTrue(minBudget < recommended && recommended < maxBudget);

        List<Map<String, Object>> categories = (List<Map<String, Object>>) result.get("categories");
        assertEquals(6, categories.size());
        int sum = categories.stream().mapToInt(c -> ((Number) c.get("amount")).intValue()).sum();
        assertEquals(recommended, sum);
    }

    @Test
    void recommendBudgetEvaluatesAllBudgetHealthStates() {
        Map<String, Object> base = service.recommendBudget("北京", "上海", 3, 2, "轻松漫游", null, 5);
        double recommended = ((Number) base.get("recommendedBudget")).doubleValue();

        assertEquals("INSUFFICIENT",
                service.recommendBudget("北京", "上海", 3, 2, "轻松漫游", recommended * 0.5, 5).get("budgetHealth"));
        assertEquals("TIGHT",
                service.recommendBudget("北京", "上海", 3, 2, "轻松漫游", recommended * 0.85, 5).get("budgetHealth"));
        assertEquals("BALANCED",
                service.recommendBudget("北京", "上海", 3, 2, "轻松漫游", recommended * 1.05, 5).get("budgetHealth"));
        assertEquals("AMPLE",
                service.recommendBudget("北京", "上海", 3, 2, "轻松漫游", recommended * 1.6, 5).get("budgetHealth"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recommendBudgetAppliesFoodieAndFamilyStyleBoosts() {
        Map<String, Object> normal = service.recommendBudget("成都", "西安", 3, 2, "轻松漫游", null, 6);
        Map<String, Object> foodie = service.recommendBudget("成都", "西安", 3, 2, "美食优先", null, 6);
        Map<String, Object> family = service.recommendBudget("成都", "西安", 3, 2, "亲子友好", null, 6);

        int normalMeal = findCategoryAmount((List<Map<String, Object>>) normal.get("categories"), "meal");
        int foodieMeal = findCategoryAmount((List<Map<String, Object>>) foodie.get("categories"), "meal");
        assertTrue(foodieMeal > normalMeal);

        int normalHotel = findCategoryAmount((List<Map<String, Object>>) normal.get("categories"), "hotel");
        int familyHotel = findCategoryAmount((List<Map<String, Object>>) family.get("categories"), "hotel");
        assertTrue(familyHotel > normalHotel);
    }

    @Test
    @SuppressWarnings("unchecked")
    void calculateDetailedBillUsesConfirmedActivityCostsAndFlagsOverBudget() {
        List<Map<String, Object>> activities = List.of(
                Map.of("type", "sightseeing", "name", "西湖游船", "cost", 120),
                Map.of("type", "meal", "name", "楼外楼", "cost", 360),
                Map.of("type", "sightseeing", "name", "断桥残雪") // 无费用，计入 pending
        );

        Map<String, Object> bill = service.calculateDetailedBill(
                "杭州", "上海", 3, 2, "轻松漫游", 1500.0, activities
        );

        assertEquals(2, bill.get("confirmedActivityCount"));
        assertEquals(1, bill.get("pendingActivityCount"));
        assertEquals("OVER_BUDGET", bill.get("status"));
        assertTrue(((Number) bill.get("deltaFromBudget")).intValue() < 0);

        List<Map<String, Object>> categories = (List<Map<String, Object>>) bill.get("categories");
        assertEquals(6, categories.size());

        Map<String, Object> ticketCat = categories.stream()
                .filter(c -> "ticket".equals(c.get("code"))).findFirst().orElseThrow();
        assertEquals(120, ((Number) ticketCat.get("amount")).intValue());
        assertEquals("ITINERARY_CONFIRMED", ticketCat.get("priceSource"));
    }

    @Test
    void estimateTransportAndBookingPlaceholdersReturnPlaceholderMetadata() {
        Map<String, Object> transport = service.estimateTransport("杭州", List.of(
                new DayStopsInput(1, List.of(
                        new StopInput("雷峰塔", "西湖区南山路", "sightseeing"),
                        new StopInput("西溪湿地", "西湖区天目山路", "sightseeing")
                ))
        ));
        assertEquals("PLACEHOLDER", transport.get("dataSource"));
        assertEquals("PLACEHOLDER_ROUTING", transport.get("provider"));

        var booking = service.getBookingPlaceholders("上海", "杭州", 3, 2, "轻松漫游", 4000);
        assertEquals("PLACEHOLDER_OTA_SUPPLY", booking.dataSource());
        assertEquals(2, booking.transportOptions().size());
        assertEquals(3, booking.hotelOptions().size());
    }

    private int findCategoryAmount(List<Map<String, Object>> categories, String code) {
        return categories.stream()
                .filter(c -> code.equals(c.get("code")))
                .mapToInt(c -> ((Number) c.get("amount")).intValue())
                .findFirst()
                .orElseThrow();
    }
}
