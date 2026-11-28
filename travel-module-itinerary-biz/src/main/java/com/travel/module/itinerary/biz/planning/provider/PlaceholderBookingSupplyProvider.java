package com.travel.module.itinerary.biz.planning.provider;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 机票/高铁与酒店供给占位实现（第二阶段增强版：支持出发日期、入住离店日期、模拟班次与高铁/机票智能推荐）。
 */
@Component
public class PlaceholderBookingSupplyProvider implements BookingSupplyProvider {

    @Override
    public String providerCode() {
        return "PLACEHOLDER_OTA_SUPPLY";
    }

    @Override
    public BookingPlaceholderResult searchPlaceholders(
            String departureCity,
            String destination,
            String startDate,
            int days,
            int travelers,
            String travelStyle,
            double budget,
            CityPriceBenchmarkProvider.CityPriceProfile priceProfile
    ) {
        String from = (departureCity == null || departureCity.isBlank()) ? "出发城市（待定）" : departureCity.trim();
        String to = priceProfile.matchedCity();
        int safeDays = Math.max(1, days);
        int safeTravelers = Math.max(1, travelers);
        int nights = Math.max(1, safeDays - 1);
        int rooms = (int) Math.ceil(safeTravelers / 2.0);

        LocalDate departDate = parseOrDefault(startDate);
        LocalDate returnDate = departDate.plusDays(safeDays - 1L);
        LocalDate checkOutDate = departDate.plusDays(nights);
        String departStr = departDate.toString();
        String returnStr = returnDate.toString();
        String checkOutStr = checkOutDate.toString();

        boolean preferFlight = "OVERSEAS".equals(priceProfile.cityTier())
                || "RESORT".equals(priceProfile.cityTier())
                || priceProfile.roundTripTransportPerPerson() >= 1150;

        int roundTripBase = (int) Math.round(priceProfile.roundTripTransportPerPerson());
        int railUnit = Math.max(180, (int) Math.round(roundTripBase * 0.72));
        int flightUnit = Math.max(520, (int) Math.round(roundTripBase * 1.15));

        String transportAdvice = preferFlight
                ? "两地跨度较远或为度假/海外目的地，建议优先选择直飞航班以节省首尾日精力。"
                : "城际距离适中，高铁准点率高且直达市区，综合性价比与耗时更优。";

        List<TransportOffer> transportOptions = List.of(
                new TransportOffer(
                        "ph-rail-" + Math.abs((from + to).hashCode() % 10000),
                        "HIGH_SPEED_RAIL",
                        from + " ⇄ " + to + " · 高铁二等座往返",
                        "12306 / 铁路占位通道",
                        "G" + (100 + Math.abs((from + to).hashCode() % 800)),
                        departStr + " 08:30 → 10:45",
                        returnStr + " 18:10 → 20:25",
                        "约 2h15m",
                        "早去晚回 · 直达市区高铁站",
                        railUnit,
                        railUnit * safeTravelers,
                        preferFlight ? "备选经济" : "推荐优先",
                        !preferFlight,
                        "PLACEHOLDER",
                        providerCode()
                ),
                new TransportOffer(
                        "ph-flight-" + Math.abs((to + from).hashCode() % 10000),
                        "FLIGHT",
                        from + " ⇄ " + to + " · 经济舱直飞往返",
                        "航司聚合占位通道",
                        "MU" + (5100 + Math.abs((to + from).hashCode() % 800)),
                        departStr + " 09:20 → 11:40",
                        returnStr + " 19:30 → 21:55",
                        "约 2h20m",
                        "含机建燃油估算 · 省时直达",
                        flightUnit,
                        flightUnit * safeTravelers,
                        preferFlight ? "推荐优先" : "省时直达",
                        preferFlight,
                        "PLACEHOLDER",
                        providerCode()
                )
        );

        int baseNight = (int) Math.round(priceProfile.hotelPerRoomNight());
        int ecoNight = Math.max(180, (int) Math.round(baseNight * 0.78));
        int comfortNight = Math.max(260, baseNight);
        int boutiqueNight = Math.max(420, (int) Math.round(baseNight * 1.45));

        List<HotelOffer> hotelOptions = List.of(
                new HotelOffer(
                        "ph-hotel-eco",
                        to + "核心地铁口精选商务酒店",
                        "市中心地铁沿线（交通便利）",
                        "双床/大床房 · 含双早",
                        departStr,
                        checkOutStr,
                        1.8,
                        nights,
                        rooms,
                        ecoNight,
                        ecoNight * nights * rooms,
                        4.6,
                        "入住前 24 小时免费取消（占位政策）",
                        "PLACEHOLDER",
                        providerCode()
                ),
                new HotelOffer(
                        "ph-hotel-comfort",
                        to + "热门商圈高评分舒适酒店",
                        "核心游览片区（近首日主景点）",
                        "高级景致房 · 含双早",
                        departStr,
                        checkOutStr,
                        0.9,
                        nights,
                        rooms,
                        comfortNight,
                        comfortNight * nights * rooms,
                        4.8,
                        "入住当天 18:00 前可免费取消（占位政策）",
                        "PLACEHOLDER",
                        providerCode()
                ),
                new HotelOffer(
                        "ph-hotel-boutique",
                        to + "设计感度假/轻奢酒店",
                        "景观核心区（体验优先）",
                        "行政/景观房 · 含双早及延迟退房",
                        departStr,
                        checkOutStr,
                        0.6,
                        nights,
                        rooms,
                        boutiqueNight,
                        boutiqueNight * nights * rooms,
                        4.9,
                        "支持灵活改期（占位政策）",
                        "PLACEHOLDER",
                        providerCode()
                )
        );

        return new BookingPlaceholderResult(
                from,
                to,
                departStr,
                returnStr,
                transportAdvice,
                transportOptions,
                hotelOptions,
                "当前机票/高铁与酒店方案为占位演示数据（已联动出发/返程日期与人数间夜）；切换选项将实时重算总账单，后续阶段直接替换为真实 OTA/联盟接口。",
                providerCode()
        );
    }

    private LocalDate parseOrDefault(String startDate) {
        if (startDate != null && !startDate.isBlank()) {
            try {
                return LocalDate.parse(startDate.trim());
            } catch (DateTimeParseException ignored) {
                // fallback to tomorrow
            }
        }
        return LocalDate.now().plusDays(1);
    }
}
