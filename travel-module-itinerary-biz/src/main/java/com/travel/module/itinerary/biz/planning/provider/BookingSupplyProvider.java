package com.travel.module.itinerary.biz.planning.provider;

import java.util.List;

/**
 * 外部机票/高铁与酒店供给提供者接口。
 * 第一/二阶段由占位实现提供结构化方案（含出发/返程日期、班次时刻、入住离店日期与商圈距离），
 * 后续通过配置开关无缝切换真实 OTA / 联盟 API。
 */
public interface BookingSupplyProvider {

    String providerCode();

    BookingPlaceholderResult searchPlaceholders(
            String departureCity,
            String destination,
            String startDate,
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
            String codeNo,
            String outboundSchedule,
            String returnSchedule,
            String durationText,
            String scheduleSummary,
            int unitPrice,
            int totalPrice,
            String tag,
            boolean recommended,
            String bookingStatus,
            String dataSource
    ) {}

    record HotelOffer(
            String offerId,
            String name,
            String area,
            String roomType,
            String checkInDate,
            String checkOutDate,
            double distanceToCenterKm,
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
            String startDate,
            String endDate,
            String transportAdvice,
            List<TransportOffer> transportOptions,
            List<HotelOffer> hotelOptions,
            String notice,
            String dataSource
    ) {}
}
