package com.travel.mcp.server.budget.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BudgetServiceTest {

    private BudgetService service;

    @BeforeEach
    void setUp() {
        service = new BudgetService();
    }

    @Test
    void estimateDetailedReturnsSixCategoriesAndRespectsCityAndSeasonMultipliers() {
        var tier1Peak = service.estimateDetailed(3, List.of("上海"), "standard", 2, 7);
        var tier3OffPeak = service.estimateDetailed(3, List.of("大理"), "standard", 2, 11);

        assertTrue(tier1Peak.isSuccess());
        assertTrue(tier3OffPeak.isSuccess());
        assertEquals(6, tier1Peak.getCategoryBreakdown().size());
        assertEquals(1.3, tier1Peak.getCityCoefficient());
        assertEquals(1.18, tier1Peak.getSeasonMultiplier());
        assertEquals(0.85, tier3OffPeak.getCityCoefficient());
        assertEquals(0.96, tier3OffPeak.getSeasonMultiplier());
        assertTrue(tier1Peak.getTotalCost() > tier3OffPeak.getTotalCost());
    }

    @Test
    void estimateRejectsInvalidDaysOrEmptyCities() {
        assertFalse(service.estimate(0, List.of("杭州"), "standard").isSuccess());
        assertFalse(service.estimate(3, List.of(), "standard").isSuccess());
    }
}
