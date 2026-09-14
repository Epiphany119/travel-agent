package com.travel.a2a.service;

import com.travel.a2a.model.DayPlan;
import com.travel.a2a.model.LlmItineraryPlan;
import com.travel.a2a.model.MealResult;
import com.travel.a2a.model.PoiResult;
import com.travel.a2a.model.TravelPlanRequest;
import com.travel.a2a.model.TravelPlanResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItineraryPlanValidatorTest {

    private final ItineraryPlanValidator validator = new ItineraryPlanValidator();

    @Test
    void rejectsMissingDayOverlapAndCandidateOutsideToolResults() {
        TravelPlanRequest request = request(2, 100);
        TravelPlanResult toolResult = toolResult();

        LlmItineraryPlan plan = new LlmItineraryPlan(
                1,
                List.of(
                        new LlmItineraryPlan.Day(1, LocalDate.of(2026, 9, 15), List.of(
                                item(LlmItineraryPlan.ItemType.ATTRACTION, "poi:west-lake", "09:00", "11:00"),
                                item(LlmItineraryPlan.ItemType.RESTAURANT, "meal:local", "10:30", "12:00"),
                                item(LlmItineraryPlan.ItemType.RESTAURANT, "meal:not-in-candidates", "12:30", "13:30")
                        ), null)
                ),
                BigDecimal.valueOf(999));

        ItineraryPlanValidator.ValidationResult validation = validator.validate(plan, request, toolResult);

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("天数")));
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("时间重叠")));
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("不在工具候选集")));
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("缺少第 2 天")));
    }

    @Test
    void recomputesRestaurantCostFromCandidateData() {
        TravelPlanRequest request = request(1, 500);
        TravelPlanResult toolResult = toolResult();

        LlmItineraryPlan plan = new LlmItineraryPlan(
                1,
                List.of(new LlmItineraryPlan.Day(1, LocalDate.of(2026, 9, 15), List.of(
                        item(LlmItineraryPlan.ItemType.ATTRACTION, "poi:west-lake", "09:00", "11:00"),
                        item(LlmItineraryPlan.ItemType.RESTAURANT, "meal:local", "12:00", "13:00")
                ), BigDecimal.ZERO)),
                BigDecimal.ZERO);

        ItineraryPlanValidator.ValidationResult validation = validator.validate(plan, request, toolResult);

        assertTrue(validation.valid());
        assertEquals(BigDecimal.valueOf(160), validation.verifiedTotal(),
                "应按餐饮工具返回的人均价格乘人数重算，而不是信任模型 totalCost");
        assertTrue(validation.hasUnknownCosts(), "景点门票未从工具返回，不能伪造为0元");
    }

    @Test
    void fallbackRetainsOnlyNonOverlappingTrustedFragments() {
        TravelPlanRequest request = request(2, 100);
        TravelPlanResult toolResult = toolResult();
        LlmItineraryPlan invalidPlan = new LlmItineraryPlan(
                1,
                List.of(
                        new LlmItineraryPlan.Day(1, LocalDate.of(2026, 9, 15), List.of(
                                item(LlmItineraryPlan.ItemType.ATTRACTION, "poi:west-lake", "09:00", "11:00"),
                                item(LlmItineraryPlan.ItemType.RESTAURANT, "meal:local", "10:30", "12:00"),
                                item(LlmItineraryPlan.ItemType.RESTAURANT, "meal:local", "12:00", "13:00"),
                                item(LlmItineraryPlan.ItemType.RESTAURANT, "meal:unknown", "14:00", "15:00")
                        ), null),
                        new LlmItineraryPlan.Day(2, LocalDate.of(2026, 9, 20), List.of(
                                item(LlmItineraryPlan.ItemType.ATTRACTION, "poi:west-lake", "09:00", "10:00")
                        ), null)
                ),
                BigDecimal.valueOf(999));

        LlmItineraryPlan safe = validator.retainSafeFragments(invalidPlan, request, toolResult);

        assertEquals(2, safe.getDays().size());
        assertEquals(1, safe.getDays().get(0).getItems().size(),
                "冲突、重复、候选集外地点和超预算餐厅不能进入部分兜底");
        assertEquals("poi:west-lake", safe.getDays().get(0).getItems().get(0).getPlaceId());
        assertTrue(safe.getDays().get(1).getItems().isEmpty(), "错误日期的模型活动不能保留");
    }

    private TravelPlanRequest request(int days, double budget) {
        return TravelPlanRequest.builder()
                .destination("杭州")
                .days(days)
                .budget(budget)
                .travelers(2)
                .travelStyle("休闲")
                .build();
    }

    private TravelPlanResult toolResult() {
        return TravelPlanResult.builder()
                .destination("杭州")
                .days(2)
                .pois(List.of(PoiResult.builder()
                        .placeId("poi:west-lake").name("西湖").address("西湖景区").build()))
                .meals(List.of(MealResult.builder()
                        .placeId("meal:local").name("本地餐厅").address("湖滨路").avgPrice(80).build()))
                .dayPlans(List.of(
                        DayPlan.builder().day(1).date("2026-09-15").build(),
                        DayPlan.builder().day(2).date("2026-09-16").build()))
                .build();
    }

    private LlmItineraryPlan.Item item(LlmItineraryPlan.ItemType type,
                                       String placeId,
                                       String start,
                                       String end) {
        return new LlmItineraryPlan.Item(type, placeId,
                LocalTime.parse(start), LocalTime.parse(end), BigDecimal.valueOf(999), null);
    }
}
