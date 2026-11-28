package com.travel.module.itinerary.biz.planning.provider;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 第一阶段机票/高铁与酒店供给占位实现（不调用外部 OTA / 联盟平台）。
 * 基于城市物价基准与出行人数生成结构化占位报价，用于验证预算账单与预订清单链路。
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

        int roundTripBase = (int) Math.round(priceProfile.roundTripTransportPerPerson());
        int railUnit = Math.max(180, (int) Math.round(roundTripBase * 0.72));
        int flightUnit = Math.max(520, (int) Math.round(roundTripBase * 1.15));

        List<TransportOffer> transportOptions = List.of(
                new TransportOffer(
                        "ph-rail-" + Math.abs((from + to).hashCode() % 10000),
                        "HIGH_SPEED_RAIL",
                        from + " ⇄ " + to + " · 高铁二等座往返",
                        "12306 / 铁路占位通道",
                        "推荐早班去、晚班回 · 准点率高",
                        railUnit,
                        railUnit * safeTravelers,
                        "高性价比",
                        "PLACEHOLDER",
                        providerCode()
                ),
                new TransportOffer(
                        "ph-flight-" + Math.abs((to + from).hashCode() % 10000),
                        "FLIGHT",
                        from + " ⇄ " + to + " · 经济舱直飞往返",
                        "航司聚合占位通道",
                        "含机建燃油估算 · 节省长途时间",
                        flightUnit,
                        flightUnit * safeTravelers,
                        "省时直达",
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
                transportOptions,
                hotelOptions,
                "当前机票/高铁与酒店报价为第一阶段占位数据（基于当地物价基准测算），后续阶段将无缝接入真实 OTA / 联盟实时库存与返佣链接。",
                providerCode()
        );
    }
}
