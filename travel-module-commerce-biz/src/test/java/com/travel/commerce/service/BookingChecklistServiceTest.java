package com.travel.commerce.service;

import com.travel.commerce.provider.PlaceholderCpsAttributionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BookingChecklistServiceTest {

    private BookingChecklistService service;

    @BeforeEach
    void setUp() {
        // 传入 null JdbcTemplate 测试内存回退模式，确保纯单元测试零数据库依赖
        service = new BookingChecklistService(null, new PlaceholderCpsAttributionProvider());
    }

    @Test
    void createsBatchBookingsConfirmsAndAggregatesCommercialSummary() {
        List<Map<String, Object>> created = service.createBatch("user_1", Map.of(
                "destination", "新疆",
                "planId", "plan_xj_01",
                "startDate", "2026-07-01",
                "endDate", "2026-07-05",
                "items", List.of(
                        Map.of("category", "HOTEL", "title", "瑞豪国际酒店", "amount", 1600),
                        Map.of("category", "FLIGHT", "title", "上海 ⇄ 新疆 经济舱往返", "amount", 2200)
                )
        ));

        assertEquals(2, created.size());
        String hotelBookingNo = String.valueOf(created.get(0).get("bookingNo"));
        String flightBookingNo = String.valueOf(created.get(1).get("bookingNo"));

        assertEquals("PENDING", created.get(0).get("status"));
        assertTrue(String.valueOf(created.get(0).get("trackingUrl")).contains("cps-placeholder.roamly.local"));

        Map<String, Object> confirmed = service.confirmBooking("user_1", hotelBookingNo);
        assertEquals("CONFIRMED", confirmed.get("status"));
        assertNotNull(confirmed.get("confirmedAt"));

        Map<String, Object> cancelled = service.cancelBooking("user_1", flightBookingNo);
        assertEquals("CANCELLED", cancelled.get("status"));

        // 已取消订单不可再确认
        assertThrows(ResponseStatusException.class, () -> service.confirmBooking("user_1", flightBookingNo));

        Map<String, Object> summary = service.getCommercialSummary("user_1");
        assertEquals(2, summary.get("totalCount"));
        assertEquals(1, summary.get("confirmedCount"));
        assertEquals(0, summary.get("pendingCount"));
        assertEquals(1, summary.get("cancelledCount"));
        assertEquals(new BigDecimal("1600.00"), summary.get("confirmedGmv"));
        assertEquals(new BigDecimal("96.00"), summary.get("estimatedCommission"));
    }

    @Test
    void filtersBookingsByOwnerAndDestination() {
        service.createBatch("user_1", Map.of(
                "destination", "杭州",
                "items", List.of(Map.of("category", "HOTEL", "title", "西湖舒适酒店", "amount", 840))
        ));
        service.createBatch("user_2", Map.of(
                "destination", "杭州",
                "items", List.of(Map.of("category", "HOTEL", "title", "他人预订", "amount", 500))
        ));

        assertEquals(1, service.listBookings("user_1", "杭州").size());
        assertEquals(0, service.listBookings("user_1", "成都").size());
    }
}
