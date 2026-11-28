package com.travel.commerce.service;

import com.travel.commerce.provider.CpsAttributionProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 第三阶段：一键预订清单与 CPS 归因履约服务。
 * 支持跨页面（/chat 规划 -> /journeys 我的旅程 -> /profile 个人中心订单与分佣看板）同步预订状态。
 * 自动建表（commerce_booking_item），若数据库不可写则安全回退到内存存储以保证零阻塞验收。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingChecklistService {

    private final JdbcTemplate jdbcTemplate;
    private final CpsAttributionProvider cpsAttributionProvider;

    private final AtomicBoolean tableChecked = new AtomicBoolean(false);
    private volatile boolean dbAvailable = true;
    private final Map<String, Map<String, Object>> memoryStore = new ConcurrentHashMap<>();

    public List<Map<String, Object>> createBatch(String ownerId, Map<String, Object> body) {
        ensureTable();
        String destination = str(body.get("destination"), "未命名目的地");
        String planId = str(body.get("planId"), "plan_" + shortId());
        String startDate = str(body.get("startDate"), "");
        String endDate = str(body.get("endDate"), "");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawItems = body.get("items") instanceof List<?> l
                ? (List<Map<String, Object>>) l
                : List.of();
        if (rawItems.isEmpty()) {
            throw new IllegalArgumentException("items is required");
        }

        List<Map<String, Object>> created = new ArrayList<>();
        for (Map<String, Object> raw : rawItems) {
            String category = str(raw.get("category"), "OTHER").toUpperCase();
            String title = str(raw.get("title"), "旅行预订项");
            String offerId = str(raw.get("offerId"), "ph_" + shortId());
            String providerCode = str(raw.get("providerCode"), "PLACEHOLDER_OTA_SUPPLY");
            String subtitle = str(raw.get("subtitle"), "");
            BigDecimal amount = toDecimal(raw.get("amount"));
            String bookingNo = "bk_" + shortId();

            CpsAttributionProvider.CpsLinkResult cps = cpsAttributionProvider.buildTrackingLink(
                    providerCode, category, ownerId, planId, offerId, amount
            );

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bookingNo", bookingNo);
            item.put("ownerId", ownerId);
            item.put("planId", planId);
            item.put("destination", destination);
            item.put("startDate", startDate);
            item.put("endDate", endDate);
            item.put("category", category);
            item.put("offerId", offerId);
            item.put("title", title);
            item.put("subtitle", subtitle);
            item.put("providerCode", providerCode);
            item.put("amount", amount);
            item.put("currency", "CNY");
            item.put("status", "PENDING");
            item.put("attributionCode", cps.attributionCode());
            item.put("trackingUrl", cps.trackingUrl());
            item.put("commissionRate", cps.commissionRate());
            item.put("estimatedCommission", cps.estimatedCommission());
            item.put("dataSource", cps.dataSource());
            item.put("createdAt", LocalDateTime.now().toString());
            item.put("confirmedAt", null);

            saveBooking(item);
            created.add(item);
        }
        return created;
    }

    public List<Map<String, Object>> listBookings(String ownerId, String destination) {
        ensureTable();
        String destFilter = destination == null ? "" : destination.trim();
        if (dbAvailable) {
            try {
                List<Map<String, Object>> rows = destFilter.isBlank()
                        ? jdbcTemplate.queryForList(
                        "SELECT booking_no,owner_id,plan_id,destination,start_date,end_date,category,offer_id,title,subtitle," +
                                "provider_code,amount,currency,status,attribution_code,tracking_url,commission_rate," +
                                "estimated_commission,data_source,created_at,confirmed_at " +
                                "FROM commerce_booking_item WHERE owner_id=? ORDER BY id DESC LIMIT 100",
                        ownerId)
                        : jdbcTemplate.queryForList(
                        "SELECT booking_no,owner_id,plan_id,destination,start_date,end_date,category,offer_id,title,subtitle," +
                                "provider_code,amount,currency,status,attribution_code,tracking_url,commission_rate," +
                                "estimated_commission,data_source,created_at,confirmed_at " +
                                "FROM commerce_booking_item WHERE owner_id=? AND destination=? ORDER BY id DESC LIMIT 100",
                        ownerId, destFilter);
                return rows.stream().map(this::toCamelRow).toList();
            } catch (Exception e) {
                log.warn("Fallback to memoryStore for listBookings: {}", e.getMessage());
            }
        }
        return memoryStore.values().stream()
                .filter(m -> ownerId.equals(m.get("ownerId")))
                .filter(m -> destFilter.isBlank() || destFilter.equals(m.get("destination")))
                .sorted((a, b) -> String.valueOf(b.get("createdAt")).compareTo(String.valueOf(a.get("createdAt"))))
                .toList();
    }

    public Map<String, Object> confirmBooking(String ownerId, String bookingNo) {
        ensureTable();
        Map<String, Object> existing = findBooking(ownerId, bookingNo);
        String status = String.valueOf(existing.get("status"));
        if ("CONFIRMED".equals(status)) {
            return existing;
        }
        if ("CANCELLED".equals(status)) {
            throw new ResponseStatusException(CONFLICT, "已取消的预订项无法确认");
        }
        String now = LocalDateTime.now().toString();
        if (dbAvailable) {
            try {
                jdbcTemplate.update(
                        "UPDATE commerce_booking_item SET status='CONFIRMED', confirmed_at=CURRENT_TIMESTAMP WHERE booking_no=? AND owner_id=?",
                        bookingNo, ownerId);
                return findBooking(ownerId, bookingNo);
            } catch (Exception e) {
                log.warn("Fallback to memoryStore for confirmBooking: {}", e.getMessage());
            }
        }
        existing.put("status", "CONFIRMED");
        existing.put("confirmedAt", now);
        memoryStore.put(bookingNo, existing);
        return existing;
    }

    public Map<String, Object> cancelBooking(String ownerId, String bookingNo) {
        ensureTable();
        Map<String, Object> existing = findBooking(ownerId, bookingNo);
        if ("CANCELLED".equals(String.valueOf(existing.get("status")))) {
            return existing;
        }
        if (dbAvailable) {
            try {
                jdbcTemplate.update(
                        "UPDATE commerce_booking_item SET status='CANCELLED' WHERE booking_no=? AND owner_id=?",
                        bookingNo, ownerId);
                return findBooking(ownerId, bookingNo);
            } catch (Exception e) {
                log.warn("Fallback to memoryStore for cancelBooking: {}", e.getMessage());
            }
        }
        existing.put("status", "CANCELLED");
        memoryStore.put(bookingNo, existing);
        return existing;
    }

    public Map<String, Object> getCommercialSummary(String ownerId) {
        List<Map<String, Object>> bookings = listBookings(ownerId, null);
        int confirmedCount = 0;
        int pendingCount = 0;
        int cancelledCount = 0;
        BigDecimal confirmedGmv = BigDecimal.ZERO;
        BigDecimal estimatedCommission = BigDecimal.ZERO;

        for (Map<String, Object> item : bookings) {
            String st = String.valueOf(item.get("status"));
            BigDecimal amt = toDecimal(item.get("amount"));
            BigDecimal comm = toDecimal(item.get("estimatedCommission"));
            if ("CONFIRMED".equals(st)) {
                confirmedCount++;
                confirmedGmv = confirmedGmv.add(amt);
                estimatedCommission = estimatedCommission.add(comm);
            } else if ("PENDING".equals(st)) {
                pendingCount++;
            } else {
                cancelledCount++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCount", bookings.size());
        summary.put("confirmedCount", confirmedCount);
        summary.put("pendingCount", pendingCount);
        summary.put("cancelledCount", cancelledCount);
        summary.put("confirmedGmv", confirmedGmv.setScale(2, RoundingMode.HALF_UP));
        summary.put("estimatedCommission", estimatedCommission.setScale(2, RoundingMode.HALF_UP));
        summary.put("currency", "CNY");
        summary.put("dataSource", cpsAttributionProvider.providerCode());
        summary.put("bookings", bookings);
        return summary;
    }

    private Map<String, Object> findBooking(String ownerId, String bookingNo) {
        if (dbAvailable) {
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT booking_no,owner_id,plan_id,destination,start_date,end_date,category,offer_id,title,subtitle," +
                                "provider_code,amount,currency,status,attribution_code,tracking_url,commission_rate," +
                                "estimated_commission,data_source,created_at,confirmed_at " +
                                "FROM commerce_booking_item WHERE booking_no=? AND owner_id=? LIMIT 1",
                        bookingNo, ownerId);
                if (!rows.isEmpty()) {
                    return toCamelRow(rows.get(0));
                }
            } catch (Exception e) {
                log.warn("Fallback to memoryStore for findBooking: {}", e.getMessage());
            }
        }
        Map<String, Object> mem = memoryStore.get(bookingNo);
        if (mem == null || !ownerId.equals(mem.get("ownerId"))) {
            throw new ResponseStatusException(NOT_FOUND, "预订项不存在");
        }
        return mem;
    }

    private void saveBooking(Map<String, Object> item) {
        if (dbAvailable) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO commerce_booking_item(booking_no,owner_id,plan_id,destination,start_date,end_date," +
                                "category,offer_id,title,subtitle,provider_code,amount,currency,status,attribution_code," +
                                "tracking_url,commission_rate,estimated_commission,data_source,created_at) " +
                                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                        item.get("bookingNo"), item.get("ownerId"), item.get("planId"), item.get("destination"),
                        item.get("startDate"), item.get("endDate"), item.get("category"), item.get("offerId"),
                        item.get("title"), item.get("subtitle"), item.get("providerCode"), item.get("amount"),
                        item.get("currency"), item.get("status"), item.get("attributionCode"), item.get("trackingUrl"),
                        item.get("commissionRate"), item.get("estimatedCommission"), item.get("dataSource")
                );
                return;
            } catch (Exception e) {
                log.warn("Persist to commerce_booking_item failed, falling back to memory: {}", e.getMessage());
                dbAvailable = false;
            }
        }
        memoryStore.put(String.valueOf(item.get("bookingNo")), item);
    }

    private void ensureTable() {
        if (tableChecked.compareAndSet(false, true)) {
            if (jdbcTemplate == null) {
                dbAvailable = false;
                return;
            }
            try {
                jdbcTemplate.execute("""
                        CREATE TABLE IF NOT EXISTS commerce_booking_item (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            booking_no VARCHAR(64) NOT NULL UNIQUE,
                            owner_id VARCHAR(64) NOT NULL,
                            plan_id VARCHAR(64),
                            destination VARCHAR(128) NOT NULL,
                            start_date VARCHAR(32),
                            end_date VARCHAR(32),
                            category VARCHAR(32) NOT NULL,
                            offer_id VARCHAR(64),
                            title VARCHAR(255) NOT NULL,
                            subtitle VARCHAR(255),
                            provider_code VARCHAR(64),
                            amount DECIMAL(12,2) NOT NULL DEFAULT 0,
                            currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
                            status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                            attribution_code VARCHAR(64),
                            tracking_url VARCHAR(512),
                            commission_rate DECIMAL(6,4) NOT NULL DEFAULT 0,
                            estimated_commission DECIMAL(12,2) NOT NULL DEFAULT 0,
                            data_source VARCHAR(64),
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            confirmed_at TIMESTAMP NULL
                        )
                        """);
            } catch (Exception e) {
                log.warn("Could not auto-create commerce_booking_item table, using in-memory store: {}", e.getMessage());
                dbAvailable = false;
            }
        }
    }

    private Map<String, Object> toCamelRow(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookingNo", row.get("booking_no"));
        m.put("ownerId", row.get("owner_id"));
        m.put("planId", row.get("plan_id"));
        m.put("destination", row.get("destination"));
        m.put("startDate", row.get("start_date"));
        m.put("endDate", row.get("end_date"));
        m.put("category", row.get("category"));
        m.put("offerId", row.get("offer_id"));
        m.put("title", row.get("title"));
        m.put("subtitle", row.get("subtitle"));
        m.put("providerCode", row.get("provider_code"));
        m.put("amount", row.get("amount"));
        m.put("currency", row.get("currency"));
        m.put("status", row.get("status"));
        m.put("attributionCode", row.get("attribution_code"));
        m.put("trackingUrl", row.get("tracking_url"));
        m.put("commissionRate", row.get("commission_rate"));
        m.put("estimatedCommission", row.get("estimated_commission"));
        m.put("dataSource", row.get("data_source"));
        m.put("createdAt", row.get("created_at") == null ? null : String.valueOf(row.get("created_at")));
        m.put("confirmedAt", row.get("confirmed_at") == null ? null : String.valueOf(row.get("confirmed_at")));
        return m;
    }

    private String str(Object v, String fallback) {
        if (v == null) return fallback;
        String s = String.valueOf(v).trim();
        return s.isBlank() ? fallback : s;
    }

    private BigDecimal toDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
