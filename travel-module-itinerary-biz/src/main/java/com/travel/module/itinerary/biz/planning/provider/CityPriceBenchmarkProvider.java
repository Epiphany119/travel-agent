package com.travel.module.itinerary.biz.planning.provider;

/**
 * 城市物价基准提供者接口。
 * 第一阶段由内置城市物价基准库占位实现，后续可替换为外部物价指数或历史订单统计服务。
 */
public interface CityPriceBenchmarkProvider {

    CityPriceProfile resolve(String destination);

    /**
     * 获取出行月份的淡旺季系数（默认 1.0）。
     */
    double seasonalMultiplier(int month);

    /**
     * 城市消费基准画像（单位：元/人，酒店为 元/间/晚，按两人一间折算）。
     */
    record CityPriceProfile(
            String matchedCity,
            String cityTier,
            String tierLabel,
            double hotelPerRoomNight,
            double mealPerPersonDay,
            double ticketPerPersonDay,
            double localTransitPerPersonDay,
            double roundTripTransportPerPerson,
            String priceNote
    ) {}
}
