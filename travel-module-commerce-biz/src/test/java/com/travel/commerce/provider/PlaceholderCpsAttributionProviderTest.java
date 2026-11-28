package com.travel.commerce.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderCpsAttributionProviderTest {

    private PlaceholderCpsAttributionProvider provider;

    @BeforeEach
    void setUp() {
        provider = new PlaceholderCpsAttributionProvider();
    }

    @Test
    void buildsTrackingLinkAndComputesCategoryCommissionRates() {
        var hotelLink = provider.buildTrackingLink(
                "PLACEHOLDER_OTA_SUPPLY", "HOTEL", "user_1", "plan_1", "ph-hotel-comfort", new BigDecimal("1000")
        );
        assertEquals("PLACEHOLDER_CPS_AFFILIATE", hotelLink.dataSource());
        assertTrue(hotelLink.attributionCode().startsWith("cps_"));
        assertTrue(hotelLink.trackingUrl().contains("subId=" + hotelLink.attributionCode()));
        assertEquals(new BigDecimal("0.060"), hotelLink.commissionRate());
        assertEquals(new BigDecimal("60.00"), hotelLink.estimatedCommission());

        var flightLink = provider.buildTrackingLink(
                "PLACEHOLDER_OTA_SUPPLY", "FLIGHT", "user_1", "plan_1", "ph-flight-1", new BigDecimal("2000")
        );
        assertEquals(new BigDecimal("0.012"), flightLink.commissionRate());
        assertEquals(new BigDecimal("24.00"), flightLink.estimatedCommission());
    }
}
