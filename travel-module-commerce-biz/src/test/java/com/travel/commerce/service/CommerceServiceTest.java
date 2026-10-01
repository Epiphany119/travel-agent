package com.travel.commerce.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.commerce.provider.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommerceServiceTest {

    private JdbcTemplate jdbc;
    private PaymentGateway paymentGateway;
    private CommerceService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        paymentGateway = mock(PaymentGateway.class);
        service = new CommerceService(jdbc, paymentGateway, new ObjectMapper());
    }

    @Test
    void repeatedIdempotencyKeyReturnsTheExistingOrderWithoutCreatingAnotherOne() {
        String owner = "alice";
        String planId = "plan_1";
        String key = "checkout-1";
        Map<String, Object> existing = Map.of(
                "order_no", "ord_existing", "plan_id", planId, "offer_id", "offer_1",
                "amount", new BigDecimal("199.00"), "currency", "CNY",
                "status", "PENDING_PAYMENT");
        when(jdbc.queryForList(contains("FROM ai_plan"), eq(planId), eq(owner)))
                .thenReturn(List.of(Map.of("plan_id", planId)));
        when(jdbc.queryForList(contains("FROM commerce_order WHERE owner_id=? AND idempotency_key=?"),
                eq(owner), eq(key))).thenReturn(List.of(existing));

        Map<String, Object> result = service.createOrder(owner,
                Map.of("planId", planId, "offerId", "offer_1"), key);

        assertSame(existing, result);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void paymentMovesPendingOrderToPaidAndIsIdempotentForAlreadyPaidOrder() {
        String owner = "alice";
        String orderNo = "ord_1";
        Map<String, Object> pending = Map.of(
                "order_no", orderNo, "plan_id", "plan_1", "offer_id", "offer_1",
                "provider_code", "sandbox", "amount", new BigDecimal("199.00"),
                "currency", "CNY", "status", "PENDING_PAYMENT");
        Map<String, Object> paid = Map.of(
                "order_no", orderNo, "plan_id", "plan_1", "offer_id", "offer_1",
                "provider_code", "sandbox", "amount", new BigDecimal("199.00"),
                "currency", "CNY", "status", "PAID");
        when(jdbc.queryForList(contains("FROM commerce_order WHERE order_no=? AND owner_id=?"),
                eq(orderNo), eq(owner))).thenReturn(List.of(pending), List.of(paid));
        when(jdbc.update(startsWith("UPDATE commerce_order SET status='PAID'"), eq(orderNo), eq(owner)))
                .thenReturn(1);
        when(paymentGateway.pay(eq(orderNo), eq(new BigDecimal("199.00")), eq("CNY")))
                .thenReturn(new PaymentGateway.PaymentResult(true, "evt_1", "sandbox", "accepted"));

        Map<String, Object> result = service.pay(owner, orderNo);

        assertEquals("PAID", result.get("status"));
        verify(paymentGateway).pay(orderNo, new BigDecimal("199.00"), "CNY");
        verify(jdbc).update(startsWith("INSERT INTO commerce_payment_event"), any(Object[].class));

        when(jdbc.queryForList(contains("FROM commerce_order WHERE order_no=? AND owner_id=?"),
                eq(orderNo), eq(owner))).thenReturn(List.of(paid));
        assertSame(paid, service.pay(owner, orderNo));
        verifyNoMoreInteractions(paymentGateway);
    }

    @Test
    void planOwnershipIsCheckedBeforeOfferOrOrderWrites() {
        when(jdbc.queryForList(contains("FROM ai_plan"), eq("plan_2"), eq("bob")))
                .thenReturn(List.of());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.createOrder("bob", Map.of("planId", "plan_2", "offerId", "offer_1"), "k"));

        assertEquals(404, exception.getStatusCode().value());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
