package com.travel.commerce.controller;

import com.travel.commerce.service.CommerceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 商业化闭环 API。所有资源以认证用户和计划归属为边界。 */
@RestController
@RequestMapping("/api/commerce")
@RequiredArgsConstructor
public class CommerceController {
    private final CommerceService commerceService;

    @GetMapping("/offers")
    public List<Map<String, Object>> offers(@RequestParam(required = false) String planId,
                                            @RequestParam(required = false, defaultValue = "") String destination,
                                            HttpServletRequest request) {
        return commerceService.listOffers(currentUserId(request), planId, destination);
    }

    @PostMapping("/clicks")
    public Map<String, Object> click(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return commerceService.recordClick(currentUserId(request), body);
    }

    @PostMapping("/orders")
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> body,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                           HttpServletRequest request) {
        return commerceService.createOrder(currentUserId(request), body, idempotencyKey);
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders(HttpServletRequest request) {
        return commerceService.listOrders(currentUserId(request));
    }

    @PostMapping("/orders/{orderNo}/pay")
    public Map<String, Object> pay(@PathVariable String orderNo, HttpServletRequest request) {
        return commerceService.pay(currentUserId(request), orderNo);
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public Map<String, Object> cancel(@PathVariable String orderNo, HttpServletRequest request) {
        return commerceService.cancel(currentUserId(request), orderNo);
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
