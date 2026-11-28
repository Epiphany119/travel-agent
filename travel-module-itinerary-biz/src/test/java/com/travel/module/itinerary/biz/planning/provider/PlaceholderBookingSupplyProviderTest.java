package com.travel.module.itinerary.biz.planning.provider;

import com.travel.module.itinerary.biz.planning.provider.BookingSupplyProvider.BookingPlaceholderResult;
import com.travel.module.itinerary.biz.planning.provider.BookingSupplyProvider.HotelOffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderBookingSupplyProviderTest {

    private LocalCityPriceBenchmarkProvider priceProvider;
    private PlaceholderBookingSupplyProvider supplyProvider;

    @BeforeEach
    void setUp() {
        priceProvider = new LocalCityPriceBenchmarkProvider();
        supplyProvider = new PlaceholderBookingSupplyProvider();
    }

    @Test
    void generatesDateLinkedTransportAndThreeIncreasingHotelTiers() {
        var profile = priceProvider.resolve("杭州");
        BookingPlaceholderResult result = supplyProvider.searchPlaceholders(
                "上海", "杭州", "2026-05-01", 4, 3, "轻松漫游", 5000, profile
        );

        assertEquals("上海", result.departureCity());
        assertEquals("杭州", result.destination());
        assertEquals("2026-05-01", result.startDate());
        assertEquals("2026-05-04", result.endDate());
        assertEquals("PLACEHOLDER_OTA_SUPPLY", result.dataSource());
        assertEquals(2, result.transportOptions().size());
        assertEquals(3, result.hotelOptions().size());
        // 短中程推荐高铁
        assertTrue(result.transportOptions().get(0).recommended());
        assertFalse(result.transportOptions().get(1).recommended());

        // 3 位旅客需 2 间房，4 天住 3 晚
        for (HotelOffer hotel : result.hotelOptions()) {
            assertEquals("2026-05-01", hotel.checkInDate());
            assertEquals("2026-05-04", hotel.checkOutDate());
            assertEquals(3, hotel.nights());
            assertEquals(2, hotel.rooms());
            assertEquals(hotel.pricePerNight() * 3 * 2, hotel.totalPrice());
            assertEquals("PLACEHOLDER", hotel.bookingStatus());
            assertEquals("PLACEHOLDER_OTA_SUPPLY", hotel.dataSource());
        }

        assertTrue(result.hotelOptions().get(0).pricePerNight() < result.hotelOptions().get(1).pricePerNight());
        assertTrue(result.hotelOptions().get(1).pricePerNight() < result.hotelOptions().get(2).pricePerNight());
    }

    @Test
    void prefersFlightForOverseasOrResortDestinationAndClampsMinimumNights() {
        var tokyoProfile = priceProvider.resolve("东京");
        BookingPlaceholderResult result = supplyProvider.searchPlaceholders(
                "  ", "东京", "2026-08-10", 1, 1, "美食优先", 8000, tokyoProfile
        );

        assertEquals("出发城市（待定）", result.departureCity());
        assertFalse(result.transportOptions().get(0).recommended());
        assertTrue(result.transportOptions().get(1).recommended());
        assertEquals(1, result.hotelOptions().get(0).nights());
        assertEquals(1, result.hotelOptions().get(0).rooms());
        assertEquals(result.transportOptions().get(0).unitPrice(), result.transportOptions().get(0).totalPrice());
    }
}
