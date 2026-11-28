package com.travel.module.itinerary.biz.planning.provider;

import java.util.List;

/**
 * 外部机票/高铁与酒店供给提供者接口。
 * 第一阶段由占位实现生成结构化推荐方案，第二/三阶段接入真实 OTA/联盟 API。
 */
public interface BookingSupplyProvider {

    String providerCode();

    BookingPlaceholderResult searchPlaceholders(
            String departureCity,
            String destination,
            int days,
            int travelers,
            String travelStyle,
            double budget,
            CityPriceBenchmarkProvider.CityPriceProfile priceProfile
    );

    record TransportOffer(
            String offerId,
            String category,
            String title,
            String carrier,
            String scheduleSummary,
            int unitPrice,
            int totalPrice,
            String tag,
            String bookingStatus,
            String dataSource
    ) {}

    record HotelOffer(
            String offerId,
            String name,
            String area,
            String roomType,
            int nights,
            int rooms,
            int pricePerNight,
            int totalPrice,
            double rating,
            String cancelPolicy,
            String bookingStatus,
            String dataSource
    ) {}

    record BookingPlaceholderResult(
            String departureCity,
            String destination,
            List<TransportOffer> transportOptions,
            List<HotelOffer> hotelOptions,
            String notice,
            String dataSource
    ) {}
}
