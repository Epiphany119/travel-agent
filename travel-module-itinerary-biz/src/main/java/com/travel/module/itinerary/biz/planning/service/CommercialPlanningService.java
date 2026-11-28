package com.travel.module.itinerary.biz.planning.service;

import com.travel.module.itinerary.biz.planning.provider.BookingSupplyProvider;
import com.travel.module.itinerary.biz.planning.provider.CityPriceBenchmarkProvider;
import com.travel.module.itinerary.biz.planning.provider.TransportRoutingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第一/二阶段商业闭环核心服务：
 * 1. 基于城市物价基准与出发日期淡旺季的智能预算推荐（六大类拆解 + 预算健康度诊断）
 * 2. 详细费用账单核算（支持机酒选型实时联动 + 行程明确费用 + 基准估算）
 * 3. 地图距离与交通规划 + 单日同片区顺路重排（通过 TransportRoutingProvider 解耦）
 * 4. 机酒供给占位推荐（联动出发日期/返程日期/入住离店日期）
 */
@Service
@RequiredArgsConstructor
public class CommercialPlanningService {

    private final CityPriceBenchmarkProvider priceBenchmarkProvider;
    private final TransportRoutingProvider transportRoutingProvider;
    private final BookingSupplyProvider bookingSupplyProvider;

    public Map<String, Object> recommendBudget(
            String destination,
            String departureCity,
            String startDate,
            int days,
            int travelers,
            String travelStyle,
            Double userBudget,
            Integer travelMonth
    ) {
        int safeDays = Math.max(1, Math.min(30, days));
        int safeTravelers = Math.max(1, Math.min(20, travelers));
        LocalDate departDate = parseStartDate(startDate);
        LocalDate endDate = departDate.plusDays(safeDays - 1L);
        int month = (travelMonth != null && travelMonth >= 1 && travelMonth <= 12)
                ? travelMonth
                : departDate.getMonthValue();

        CityPriceBenchmarkProvider.CityPriceProfile profile = priceBenchmarkProvider.resolve(destination);
        double seasonFactor = priceBenchmarkProvider.seasonalMultiplier(month);
        String seasonLabel = seasonFactor >= 1.15 ? "旺季" : (seasonFactor >= 1.05 ? "平旺季" : "淡季");
        double styleFactor = styleMultiplier(travelStyle);

        int nights = Math.max(1, safeDays - 1);
        int rooms = (int) Math.ceil(safeTravelers / 2.0);

        int intercityTransport = roundTen(profile.roundTripTransportPerPerson() * safeTravelers * (seasonFactor > 1.1 ? 1.08 : 1.0));
        int hotel = roundTen(profile.hotelPerRoomNight() * nights * rooms * seasonFactor * styleFactor);
        int meal = roundTen(profile.mealPerPersonDay() * safeDays * safeTravelers * mealStyleBoost(travelStyle));
        int tickets = roundTen(profile.ticketPerPersonDay() * safeDays * safeTravelers * (styleFactor > 1.1 ? 1.1 : 1.0));
        int localTransit = roundTen(profile.localTransitPerPersonDay() * safeDays * safeTravelers);
        int subtotal = intercityTransport + hotel + meal + tickets + localTransit;
        int contingency = roundTen(subtotal * 0.10);
        int recommended = subtotal + contingency;

        int minBudget = roundTen(recommended * 0.78);
        int maxBudget = roundTen(recommended * 1.35);

        String health = "BALANCED";
        String advice = String.format(
                "%s属于%s，%d月处于%s（系数 %.2fx），%d天%d人建议预算 ¥%,d–¥%,d（含约10%%机动预备金）。%s。",
                profile.matchedCity(), profile.tierLabel(), month, seasonLabel, seasonFactor,
                safeDays, safeTravelers, minBudget, maxBudget, profile.priceNote()
        );

        if (userBudget != null && userBudget > 0) {
            double ratio = userBudget / Math.max(1.0, recommended);
            if (ratio < 0.72) {
                health = "INSUFFICIENT";
                advice = String.format(
                        "当前预算 ¥%,d 低于%s%s基准下限（约 ¥%,d），建议上调至 ¥%,d 或调整酒店/大交通档位。",
                        Math.round(userBudget), profile.matchedCity(), seasonLabel, minBudget, recommended
                );
            } else if (ratio < 0.92) {
                health = "TIGHT";
                advice = String.format(
                        "当前预算 ¥%,d 略偏紧（%d月%s推荐约 ¥%,d），建议优先锁定高性价比酒店并多用轨道交通。",
                        Math.round(userBudget), month, seasonLabel, recommended
                );
            } else if (ratio > 1.35) {
                health = "AMPLE";
                advice = String.format(
                        "当前预算 ¥%,d 非常充裕（当地舒适参考约 ¥%,d），可升级核心商圈品质酒店或特色体验。",
                        Math.round(userBudget), recommended
                );
            }
        }

        List<Map<String, Object>> categories = List.of(
                categoryItem("intercity_transport", "往返大交通", intercityTransport, recommended, "CITY_BENCHMARK",
                        departureCity != null && !departureCity.isBlank() ? departureCity + " ⇄ " + profile.matchedCity() + " 往返估算" : "高铁/机票往返均值估算"),
                categoryItem("hotel", "酒店住宿", hotel, recommended, "CITY_BENCHMARK",
                        String.format("%d 晚 × %d 间（%s %s均价基准）", nights, rooms, profile.matchedCity(), seasonLabel)),
                categoryItem("meal", "当地餐饮", meal, recommended, "CITY_BENCHMARK",
                        String.format("%d 天 × %d 人 · 含正餐与特色小吃", safeDays, safeTravelers)),
                categoryItem("ticket", "景点门票与体验", tickets, recommended, "CITY_BENCHMARK",
                        "按当地主要收费景区日均基准测算"),
                categoryItem("local_transit", "市内交通", localTransit, recommended, "CITY_BENCHMARK",
                        "地铁、公交与短途打车综合估算"),
                categoryItem("contingency", "机动预备金 (10%)", contingency, recommended, "CITY_BENCHMARK",
                        "用于临时天气调整、特产或突发支出")
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("destination", profile.matchedCity());
        result.put("departureCity", departureCity == null ? "" : departureCity.trim());
        result.put("startDate", departDate.toString());
        result.put("endDate", endDate.toString());
        result.put("travelMonth", month);
        result.put("seasonLabel", seasonLabel);
        result.put("cityTier", profile.cityTier());
        result.put("tierLabel", profile.tierLabel());
        result.put("days", safeDays);
        result.put("travelers", safeTravelers);
        result.put("travelStyle", travelStyle == null || travelStyle.isBlank() ? "轻松漫游" : travelStyle);
        result.put("seasonFactor", seasonFactor);
        result.put("minBudget", minBudget);
        result.put("recommendedBudget", recommended);
        result.put("maxBudget", maxBudget);
        result.put("perPersonBudget", Math.round((double) recommended / safeTravelers));
        result.put("perDayBudget", Math.round((double) recommended / safeDays));
        result.put("budgetHealth", health);
        result.put("advice", advice);
        result.put("priceNote", profile.priceNote());
        result.put("categories", categories);
        result.put("dataSource", "CITY_BENCHMARK_PLACEHOLDER");
        return result;
    }

    public Map<String, Object> calculateDetailedBill(
            String destination,
            String departureCity,
            String startDate,
            int days,
            int travelers,
            String travelStyle,
            double userBudget,
            List<Map<String, Object>> activities,
            Integer selectedTransportTotal,
            Integer selectedHotelTotal
    ) {
        Map<String, Object> baseline = recommendBudget(destination, departureCity, startDate, days, travelers, travelStyle, userBudget, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> baseCategories = (List<Map<String, Object>>) baseline.get("categories");

        int knownTicketTotal = 0;
        int knownMealTotal = 0;
        int knownTransitTotal = 0;
        int confirmedItems = 0;
        int pendingItems = 0;

        if (activities != null) {
            for (Map<String, Object> act : activities) {
                Object costObj = act.get("cost");
                String type = String.valueOf(act.getOrDefault("type", "sightseeing"));
                if (costObj instanceof Number num && num.doubleValue() >= 0) {
                    int costVal = (int) Math.round(num.doubleValue());
                    confirmedItems++;
                    if ("meal".equalsIgnoreCase(type)) {
                        knownMealTotal += costVal;
                    } else if ("transport".equalsIgnoreCase(type)) {
                        knownTransitTotal += costVal;
                    } else {
                        knownTicketTotal += costVal;
                    }
                } else {
                    pendingItems++;
                }
            }
        }

        int baseIntercity = extractAmount(baseCategories, "intercity_transport");
        int baseHotel = extractAmount(baseCategories, "hotel");
        int baseMeal = extractAmount(baseCategories, "meal");
        int baseTicket = extractAmount(baseCategories, "ticket");
        int baseTransit = extractAmount(baseCategories, "local_transit");

        boolean hasCustomTransport = selectedTransportTotal != null && selectedTransportTotal > 0;
        boolean hasCustomHotel = selectedHotelTotal != null && selectedHotelTotal > 0;

        int finalIntercity = hasCustomTransport ? selectedTransportTotal : baseIntercity;
        int finalHotel = hasCustomHotel ? selectedHotelTotal : baseHotel;
        int finalMeal = knownMealTotal > 0 ? Math.max(knownMealTotal, (int) Math.round(baseMeal * 0.6)) : baseMeal;
        int finalTicket = knownTicketTotal > 0 ? knownTicketTotal : baseTicket;
        int finalTransit = knownTransitTotal > 0 ? knownTransitTotal : baseTransit;
        int subtotal = finalIntercity + finalHotel + finalMeal + finalTicket + finalTransit;
        int finalContingency = roundTen(subtotal * 0.10);
        int totalEstimated = subtotal + finalContingency;

        double safeUserBudget = Math.max(1.0, userBudget);
        double utilizationRate = Math.round((totalEstimated / safeUserBudget) * 1000.0) / 10.0;
        String status = utilizationRate <= 92.0 ? "UNDER_BUDGET" : (utilizationRate <= 108.0 ? "ON_TRACK" : "OVER_BUDGET");
        int safeTravelers = Math.max(1, travelers);
        int safeDays = Math.max(1, days);

        List<Map<String, Object>> categories = List.of(
                categoryItem("intercity_transport", "往返大交通", finalIntercity, totalEstimated,
                        "PLACEHOLDER_QUOTE",
                        hasCustomTransport ? "已按当前所选机票/高铁占位方案联动核算" : "机票/高铁占位基准"),
                categoryItem("hotel", "酒店住宿", finalHotel, totalEstimated,
                        "PLACEHOLDER_QUOTE",
                        hasCustomHotel ? "已按当前所选酒店档位与间夜实时核算" : "商圈酒店均价占位基准"),
                categoryItem("meal", "当地餐饮", finalMeal, totalEstimated,
                        knownMealTotal > 0 ? "ITINERARY_CONFIRMED" : "CITY_BENCHMARK",
                        knownMealTotal > 0 ? "结合行程推荐餐厅人均与基准补齐" : "按当地餐饮均价估算"),
                categoryItem("ticket", "景点门票与体验", finalTicket, totalEstimated,
                        knownTicketTotal > 0 ? "ITINERARY_CONFIRMED" : "CITY_BENCHMARK",
                        knownTicketTotal > 0 ? "已按行程节点门票明细汇总" : "按当地景区门票基准估算"),
                categoryItem("local_transit", "市内交通", finalTransit, totalEstimated,
                        knownTransitTotal > 0 ? "PLACEHOLDER_ROUTING" : "CITY_BENCHMARK",
                        "基于行程节点间距与通勤方式估算"),
                categoryItem("contingency", "机动预备金 (10%)", finalContingency, totalEstimated, "CITY_BENCHMARK",
                        "应对临时变动与弹性消费")
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("destination", baseline.get("destination"));
        result.put("startDate", baseline.get("startDate"));
        result.put("endDate", baseline.get("endDate"));
        result.put("userBudget", Math.round(userBudget));
        result.put("totalEstimatedCost", totalEstimated);
        result.put("deltaFromBudget", Math.round(userBudget) - totalEstimated);
        result.put("perPersonCost", Math.round((double) totalEstimated / safeTravelers));
        result.put("perDayCost", Math.round((double) totalEstimated / safeDays));
        result.put("utilizationRate", utilizationRate);
        result.put("status", status);
        result.put("confirmedActivityCount", confirmedItems);
        result.put("pendingActivityCount", pendingItems);
        result.put("categories", categories);
        result.put("dataSource", "PHASE2_LINKED_BENCHMARK");
        return result;
    }

    public Map<String, Object> estimateTransport(String destination, List<TransportRoutingProvider.DayStopsInput> days) {
        List<TransportRoutingProvider.DayTransportPlan> plans = transportRoutingProvider.estimateDays(destination, days);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("destination", destination == null ? "" : destination.trim());
        result.put("provider", transportRoutingProvider.providerCode());
        result.put("dataSource", "PLACEHOLDER");
        result.put("notice", "当前距离、耗时与打车费用由占位路径引擎估算，可通过 travel.planning.routing.provider=amap 切换高德路径规划适配器。");
        result.put("days", plans);
        return result;
    }

    public TransportRoutingProvider.RouteOptimizationResult optimizeDayTransport(
            String destination,
            TransportRoutingProvider.DayStopsInput dayInput
    ) {
        return transportRoutingProvider.optimizeDayOrder(destination, dayInput);
    }

    public BookingSupplyProvider.BookingPlaceholderResult getBookingPlaceholders(
            String departureCity,
            String destination,
            String startDate,
            int days,
            int travelers,
            String travelStyle,
            double budget
    ) {
        CityPriceBenchmarkProvider.CityPriceProfile profile = priceBenchmarkProvider.resolve(destination);
        return bookingSupplyProvider.searchPlaceholders(
                departureCity, destination, startDate, days, travelers, travelStyle, budget, profile
        );
    }

    private LocalDate parseStartDate(String startDate) {
        if (startDate != null && !startDate.isBlank()) {
            try {
                return LocalDate.parse(startDate.trim());
            } catch (DateTimeParseException ignored) {
                // fallback to tomorrow
            }
        }
        return LocalDate.now().plusDays(1);
    }

    private Map<String, Object> categoryItem(String code, String label, int amount, int total, String source, String note) {
        double ratio = total > 0 ? Math.round((amount * 1000.0) / total) / 10.0 : 0.0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label", label);
        m.put("amount", amount);
        m.put("ratio", ratio);
        m.put("priceSource", source);
        m.put("note", note);
        return m;
    }

    private int extractAmount(List<Map<String, Object>> categories, String code) {
        for (Map<String, Object> item : categories) {
            if (code.equals(item.get("code")) && item.get("amount") instanceof Number num) {
                return num.intValue();
            }
        }
        return 0;
    }

    private double styleMultiplier(String travelStyle) {
        if (travelStyle == null) return 1.0;
        return switch (travelStyle.trim()) {
            case "亲子友好" -> 1.15;
            case "深度人文" -> 1.05;
            case "美食优先" -> 1.08;
            default -> 1.0;
        };
    }

    private double mealStyleBoost(String travelStyle) {
        if (travelStyle == null) return 1.0;
        return "美食优先".equals(travelStyle.trim()) ? 1.25 : 1.0;
    }

    private int roundTen(double value) {
        return Math.max(0, (int) (Math.round(value / 10.0) * 10));
    }
}
