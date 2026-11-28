package com.travel.module.itinerary.biz.planning.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalCityPriceBenchmarkProviderTest {

    private LocalCityPriceBenchmarkProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LocalCityPriceBenchmarkProvider();
    }

    @Test
    void resolvesKnownCitiesAcrossTiersAndSupportsFuzzyMatch() {
        var shanghai = provider.resolve("上海");
        assertEquals("TIER_1", shanghai.cityTier());
        assertEquals("上海", shanghai.matchedCity());
        assertTrue(shanghai.hotelPerRoomNight() > 500);

        var hangzhouFuzzy = provider.resolve("杭州市西湖区");
        assertEquals("杭州", hangzhouFuzzy.matchedCity());
        assertEquals("TIER_2", hangzhouFuzzy.cityTier());

        var sanya = provider.resolve("三亚");
        assertEquals("RESORT", sanya.cityTier());

        var tokyo = provider.resolve("东京");
        assertEquals("OVERSEAS", tokyo.cityTier());
        assertTrue(tokyo.roundTripTransportPerPerson() >= 3000);
    }

    @Test
    void fallsBackToDefaultTier2ProfileForUnknownOrBlankCity() {
        var unknown = provider.resolve("某未知小城");
        assertEquals("某未知小城", unknown.matchedCity());
        assertEquals("TIER_2", unknown.cityTier());
        assertEquals(360.0, unknown.hotelPerRoomNight());

        var blank = provider.resolve("   ");
        assertEquals("通用目的地", blank.matchedCity());
        assertEquals("TIER_2", blank.cityTier());
    }

    @Test
    void returnsExpectedSeasonalMultipliers() {
        assertEquals(1.18, provider.seasonalMultiplier(7));
        assertEquals(1.18, provider.seasonalMultiplier(10));
        assertEquals(1.08, provider.seasonalMultiplier(5));
        assertEquals(0.96, provider.seasonalMultiplier(11));
        assertEquals(0.96, provider.seasonalMultiplier(3));
    }
}
