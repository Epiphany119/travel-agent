package com.travel.commerce.controller;

import com.travel.commerce.service.BookingChecklistService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 第三阶段商业化闭环接口：一键预订清单、CPS 归因占位、预订状态同步与个人中心分佣看板。
 */
@RestController
@RequestMapping("/api/commerce")
@RequiredArgsConstructor
public class BookingChecklistController {

    private final BookingChecklistService bookingChecklistService;

    @PostMapping("/bookings/batch")
    public List<Map<String, Object>> createBatch(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return bookingChecklistService.createBatch(currentUserId(request), body);
    }

    @GetMapping("/bookings")
    public List<Map<String, Object>> listBookings(
            @RequestParam(required = false, defaultValue = "") String destination,
            HttpServletRequest request
    ) {
        return bookingChecklistService.listBookings(currentUserId(request), destination);
    }

    @PostMapping("/bookings/{bookingNo}/confirm")
    public Map<String, Object> confirmBooking(@PathVariable String bookingNo, HttpServletRequest request) {
        return bookingChecklistService.confirmBooking(currentUserId(request), bookingNo);
    }

    @PostMapping("/bookings/{bookingNo}/cancel")
    public Map<String, Object> cancelBooking(@PathVariable String bookingNo, HttpServletRequest request) {
        return bookingChecklistService.cancelBooking(currentUserId(request), bookingNo);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(HttpServletRequest request) {
        return bookingChecklistService.getCommercialSummary(currentUserId(request));
    }

    private String currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute("authenticatedUserId");
        if (value == null || value.toString().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return value.toString();
    }
}
