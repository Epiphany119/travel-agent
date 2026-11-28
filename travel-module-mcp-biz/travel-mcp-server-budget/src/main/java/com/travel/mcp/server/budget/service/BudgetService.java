package com.travel.mcp.server.budget.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BudgetService {

    private static final double BASE_COST_PER_DAY = 200.0;
    private static final double DAILY_COST = 100.0;

    private static final Map<String, Double> LEVEL_MULTIPLIERS = Map.of(
            "economy", 0.78,
            "standard", 1.0,
            "luxury", 1.55
    );

    private static final double TIER1_COEFFICIENT = 1.3;
    private static final double TIER2_COEFFICIENT = 1.0;
    private static final double TIER3_COEFFICIENT = 0.85;

    private static final List<String> TIER1_CITIES = List.of(
            "北京", "上海", "广州", "深圳", "香港", "澳门", "东京", "新加坡"
    );

    private static final List<String> TIER3_CITIES = List.of(
            "拉萨", "大理", "丽江", "桂林", "西宁", "银川", "呼和浩特", "乌鲁木齐"
    );

    public BudgetEstimate estimate(int days, List<String> cities, String level) {
        return estimateDetailed(days, cities, level, 1, LocalDate.now().getMonthValue());
    }

    public BudgetEstimate estimateDetailed(int days, List<String> cities, String level, int travelers, int month) {
        if (days <= 0) {
            return BudgetEstimate.failure("天数必须大于0");
        }
        if (cities == null || cities.isEmpty()) {
            return BudgetEstimate.failure("城市列表不能为空");
        }

        int safeTravelers = Math.max(1, travelers);
        String normalizedLevel = level != null ? level.toLowerCase() : "standard";
        double levelMultiplier = LEVEL_MULTIPLIERS.getOrDefault(normalizedLevel, 1.0);
        double cityCoefficient = calculateCityCoefficient(cities);
        double seasonMultiplier = calculateSeasonMultiplier(month);

        int nights = Math.max(1, days - 1);
        int rooms = (int) Math.ceil(safeTravelers / 2.0);

        double intercityTransport = roundTen(820.0 * cityCoefficient * safeTravelers);
        double hotel = roundTen(360.0 * cityCoefficient * nights * rooms * levelMultiplier * seasonMultiplier);
        double meal = roundTen(135.0 * cityCoefficient * days * safeTravelers * levelMultiplier);
        double ticket = roundTen(80.0 * cityCoefficient * days * safeTravelers);
        double localTransit = roundTen(40.0 * cityCoefficient * days * safeTravelers);
        double subtotal = intercityTransport + hotel + meal + ticket + localTransit;
        double contingency = roundTen(subtotal * 0.10);
        double totalCost = subtotal + contingency;

        Map<String, Double> categories = new LinkedHashMap<>();
        categories.put("intercityTransport", intercityTransport);
        categories.put("hotel", hotel);
        categories.put("meal", meal);
        categories.put("ticket", ticket);
        categories.put("localTransit", localTransit);
        categories.put("contingency", contingency);

        return BudgetEstimate.success(days, cities, normalizedLevel, cityCoefficient, totalCost, categories, seasonMultiplier);
    }

    private double calculateCityCoefficient(List<String> cities) {
        for (String city : cities) {
            if (city != null && TIER1_CITIES.stream().anyMatch(city::contains)) {
                return TIER1_COEFFICIENT;
            }
        }
        for (String city : cities) {
            if (city != null && TIER3_CITIES.stream().anyMatch(city::contains)) {
                return TIER3_COEFFICIENT;
            }
        }
        return TIER2_COEFFICIENT;
    }

    private double calculateSeasonMultiplier(int month) {
        return switch (month) {
            case 1, 2, 7, 8, 10 -> 1.18;
            case 4, 5, 9 -> 1.08;
            default -> 0.96;
        };
    }

    private double roundTen(double value) {
        return Math.round(value / 10.0) * 10.0;
    }

    public static class BudgetEstimate {
        private boolean success;
        private String error;
        private Integer days;
        private List<String> cities;
        private String level;
        private Double cityCoefficient;
        private Double seasonMultiplier;
        private Double totalCost;
        private Double costBreakdown;
        private Map<String, Double> categoryBreakdown;

        public static BudgetEstimate success(int days, List<String> cities, String level,
                                             double cityCoefficient, double totalCost,
                                             Map<String, Double> categoryBreakdown,
                                             double seasonMultiplier) {
            BudgetEstimate estimate = new BudgetEstimate();
            estimate.success = true;
            estimate.days = days;
            estimate.cities = cities;
            estimate.level = level != null ? level : "standard";
            estimate.cityCoefficient = cityCoefficient;
            estimate.seasonMultiplier = seasonMultiplier;
            estimate.totalCost = totalCost;
            estimate.costBreakdown = BASE_COST_PER_DAY + (days * DAILY_COST);
            estimate.categoryBreakdown = categoryBreakdown;
            return estimate;
        }

        public static BudgetEstimate failure(String error) {
            BudgetEstimate estimate = new BudgetEstimate();
            estimate.success = false;
            estimate.error = error;
            return estimate;
        }

        public boolean isSuccess() { return success; }
        public String getError() { return error; }
        public Integer getDays() { return days; }
        public List<String> getCities() { return cities; }
        public String getLevel() { return level; }
        public Double getCityCoefficient() { return cityCoefficient; }
        public Double getSeasonMultiplier() { return seasonMultiplier; }
        public Double getTotalCost() { return totalCost; }
        public Double getCostBreakdown() { return costBreakdown; }
        public Map<String, Double> getCategoryBreakdown() { return categoryBreakdown; }
    }
}
