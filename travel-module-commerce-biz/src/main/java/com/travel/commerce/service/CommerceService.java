package com.travel.commerce.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.commerce.provider.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** 计划商业化服务：商品推荐、点击归因、订单、支付回调和权益。 */
@Service
@RequiredArgsConstructor
public class CommerceService {
    private final JdbcTemplate jdbcTemplate;
    private final PaymentGateway paymentGateway;
    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> listOffers(String ownerId, String planId, String destination) {
        if (planId != null && !planId.isBlank()) requirePlanOwner(planId, ownerId);
        if (destination == null) destination = "";
        return jdbcTemplate.queryForList(
                "SELECT offer_id,provider_code,offer_type,title,destination,redirect_url,price,currency,commission_rate " +
                        "FROM commerce_offer WHERE status='ACTIVE' AND (?='' OR destination=? OR destination='通用') ORDER BY id DESC LIMIT 50",
                destination, destination);
    }

    @Transactional
    public Map<String, Object> recordClick(String ownerId, Map<String, Object> body) {
        String planId = required(body, "planId");
        String offerId = required(body, "offerId");
        requirePlanOwner(planId, ownerId);
        Map<String, Object> offer = findOffer(offerId);
        String clickId = "clk_" + randomId();
        String attribution = "att_" + randomId();
        jdbcTemplate.update(
                "INSERT INTO commerce_click(click_id,plan_id,owner_id,offer_id,attribution_code,created_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",
                clickId, planId, ownerId, offerId, attribution);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clickId", clickId);
        result.put("attributionCode", attribution);
        result.put("offerId", offerId);
        result.put("redirectUrl", offer.get("redirect_url"));
        return result;
    }

    @Transactional
    public Map<String, Object> createOrder(String ownerId, Map<String, Object> body, String idempotencyKey) {
        String planId = required(body, "planId");
        String offerId = required(body, "offerId");
        requirePlanOwner(planId, ownerId);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                    "SELECT order_no,plan_id,offer_id,amount,currency,status,created_at FROM commerce_order WHERE owner_id=? AND idempotency_key=? LIMIT 1",
                    ownerId, idempotencyKey);
            if (!existing.isEmpty()) return existing.get(0);
        }
        Map<String, Object> offer = findOffer(offerId);
        String orderNo = "ord_" + randomId();
        try {
            jdbcTemplate.update(
                    "INSERT INTO commerce_order(order_no,plan_id,owner_id,offer_id,provider_code,amount,currency,status,idempotency_key,created_at,updated_at) " +
                            "VALUES(?,?,?,?,?,?,?,'PENDING_PAYMENT',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    orderNo, planId, ownerId, offerId, offer.get("provider_code"), amount(offer.get("price")),
                    value(offer.get("currency"), "CNY"), blankToNull(idempotencyKey));
        } catch (DuplicateKeyException e) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                return jdbcTemplate.queryForMap(
                        "SELECT order_no,plan_id,offer_id,amount,currency,status,created_at FROM commerce_order WHERE owner_id=? AND idempotency_key=? LIMIT 1",
                        ownerId, idempotencyKey);
            }
            throw new ResponseStatusException(CONFLICT, "订单创建冲突，请重试");
        }
        return jdbcTemplate.queryForMap("SELECT order_no,plan_id,offer_id,amount,currency,status,created_at FROM commerce_order WHERE order_no=?", orderNo);
    }

    @Transactional
    public Map<String, Object> pay(String ownerId, String orderNo) {
        Map<String, Object> order = findOrder(ownerId, orderNo);
        String status = String.valueOf(order.get("status"));
        if ("PAID".equals(status)) return order;
        if (!"PENDING_PAYMENT".equals(status)) throw new ResponseStatusException(CONFLICT, "订单当前状态不能支付");
        PaymentGateway.PaymentResult payment = paymentGateway.pay(orderNo, amount(order.get("amount")), value(order.get("currency"), "CNY"));
        if (!payment.success()) throw new ResponseStatusException(CONFLICT, payment.message());
        String payload = toJson(Map.of("orderNo", orderNo, "amount", amount(order.get("amount")), "message", payment.message()));
        jdbcTemplate.update(
                "INSERT INTO commerce_payment_event(event_id,order_no,provider_code,event_type,amount,payload_json,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                payment.eventId(), orderNo, payment.providerCode(), "PAYMENT_SUCCEEDED", amount(order.get("amount")), payload);
        int updated = jdbcTemplate.update(
                "UPDATE commerce_order SET status='PAID',paid_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE order_no=? AND owner_id=? AND status='PENDING_PAYMENT'",
                orderNo, ownerId);
        if (updated != 1) throw new ResponseStatusException(CONFLICT, "订单状态已变化，请刷新");
        jdbcTemplate.update(
                "INSERT IGNORE INTO commerce_entitlement(entitlement_id,owner_id,order_no,entitlement_type,status,created_at) VALUES(?,?,?,'TRAVEL_SERVICE','ACTIVE',CURRENT_TIMESTAMP)",
                "ent_" + randomId(), ownerId, orderNo);
        return findOrder(ownerId, orderNo);
    }

    @Transactional
    public Map<String, Object> cancel(String ownerId, String orderNo) {
        Map<String, Object> order = findOrder(ownerId, orderNo);
        if (!"PENDING_PAYMENT".equals(String.valueOf(order.get("status")))) {
            throw new ResponseStatusException(CONFLICT, "只有待支付订单可以取消");
        }
        jdbcTemplate.update("UPDATE commerce_order SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE order_no=? AND owner_id=? AND status='PENDING_PAYMENT'", orderNo, ownerId);
        return findOrder(ownerId, orderNo);
    }

    public List<Map<String, Object>> listOrders(String ownerId) {
        return jdbcTemplate.queryForList(
                "SELECT order_no,plan_id,offer_id,provider_code,amount,currency,status,paid_at,cancelled_at,created_at,updated_at " +
                        "FROM commerce_order WHERE owner_id=? ORDER BY created_at DESC LIMIT 100", ownerId);
    }

    private void requirePlanOwner(String planId, String ownerId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT plan_id FROM ai_plan WHERE plan_id=? AND owner_id=? LIMIT 1", planId, ownerId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "计划不存在或无权操作");
    }

    private Map<String, Object> findOffer(String offerId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT offer_id,provider_code,offer_type,title,destination,redirect_url,price,currency,commission_rate FROM commerce_offer WHERE offer_id=? AND status='ACTIVE' LIMIT 1", offerId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "商品不存在或已下架");
        return rows.get(0);
    }

    private Map<String, Object> findOrder(String ownerId, String orderNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT order_no,plan_id,offer_id,provider_code,amount,currency,status,paid_at,cancelled_at,created_at,updated_at FROM commerce_order WHERE order_no=? AND owner_id=? LIMIT 1", orderNo, ownerId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "订单不存在");
        return rows.get(0);
    }

    private String required(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null || String.valueOf(value).isBlank() || String.valueOf(value).length() > 128) {
            throw new IllegalArgumentException(key + " is required");
        }
        return String.valueOf(value);
    }

    private BigDecimal amount(Object value) {
        try { return new BigDecimal(String.valueOf(value)); } catch (RuntimeException e) { throw new IllegalArgumentException("invalid amount"); }
    }

    private String value(Object value, String fallback) { return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String randomId() { return UUID.randomUUID().toString().replace("-", ""); }
    private String toJson(Object value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException("无法记录支付事件", e); } }
}
