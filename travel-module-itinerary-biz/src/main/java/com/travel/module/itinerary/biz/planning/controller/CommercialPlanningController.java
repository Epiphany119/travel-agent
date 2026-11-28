package com.travel.module.itinerary.biz.planning.controller;

import com.travel.module.itinerary.biz.planning.provider.BookingSupplyProvider;
import com.travel.module.itinerary.biz.planning.provider.TransportRoutingProvider;
import com.travel.module.itinerary.biz.planning.service.CommercialPlanningService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 第一/二阶段商业闭环规划接口：
 * 提供城市物价与日期淡旺季预算推荐、机酒联动详细账单核算、距离与交通规划、单日顺路重排、机酒占位方案。
 */
@RestController
@RequestMapping("/api/planning")
@RequiredArgsConstructor
public class CommercialPlanningController {

    private final CommercialPlanningService planningService;

    @PostMapping("/budget/recommend")
    public Map<String, Object> recommendBudget(@RequestBody BudgetRecommendRequest req) {
        return planningService.recommendBudget(
                req.destination(),
                req.departureCity(),
                req.startDate(),
                req.days() != null ? req.days() : 3,
                req.travelers() != null ? req.travelers() : 2,
                req.travelStyle(),
                req.budget(),
                req.travelMonth()
        );
    }

    @PostMapping("/bill/calculate")
    public Map<String, Object> calculateBill(@RequestBody DetailedBillRequest req) {
        return planningService.calculateDetailedBill(
                req.destination(),
                req.departureCity(),
                req.startDate(),
                req.days() != null ? req.days() : 3,
                req.travelers() != null ? req.travelers() : 2,
                req.travelStyle(),
                req.userBudget() != null ? req.userBudget() : 3000.0,
                req.activities(),
                req.selectedTransportTotal(),
                req.selectedHotelTotal()
        );
    }

    @PostMapping("/transport/estimate")
    public Map<String, Object> estimateTransport(@RequestBody TransportEstimateRequest req) {
        return planningService.estimateTransport(req.destination(), req.days());
    }

    @PostMapping("/transport/optimize")
    public TransportRoutingProvider.RouteOptimizationResult optimizeDayTransport(@RequestBody OptimizeDayRequest req) {
        return planningService.optimizeDayTransport(req.destination(), req.dayPlan());
    }

    @PostMapping("/booking/placeholders")
    public BookingSupplyProvider.BookingPlaceholderResult bookingPlaceholders(@RequestBody BookingPlaceholderRequest req) {
        return planningService.getBookingPlaceholders(
                req.departureCity(),
                req.destination(),
                req.startDate(),
                req.days() != null ? req.days() : 3,
                req.travelers() != null ? req.travelers() : 2,
                req.travelStyle(),
                req.budget() != null ? req.budget() : 3000.0
        );
    }

    public record BudgetRecommendRequest(
            String destination,
            String departureCity,
            String startDate,
            Integer days,
            Integer travelers,
            String travelStyle,
            Double budget,
            Integer travelMonth
    ) {}

    public record DetailedBillRequest(
            String destination,
            String departureCity,
            String startDate,
            Integer days,
            Integer travelers,
            String travelStyle,
            Double userBudget,
            List<Map<String, Object>> activities,
            Integer selectedTransportTotal,
            Integer selectedHotelTotal
    ) {}

    public record TransportEstimateRequest(
            String destination,
            List<TransportRoutingProvider.DayStopsInput> days
    ) {}

    public record OptimizeDayRequest(
            String destination,
            TransportRoutingProvider.DayStopsInput dayPlan
    ) {}

    public record BookingPlaceholderRequest(
            String departureCity,
            String destination,
            String startDate,
            Integer days,
            Integer travelers,
            String travelStyle,
            Double budget
    ) {}
}
