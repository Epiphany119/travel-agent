package com.travel.commerce.provider;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 第三阶段 CPS 联盟归因占位实现（不调用外部联盟平台）。
 * 按品类返回行业参考佣金比例并生成带归因参数的占位跳转链接。
 */
@Component
public class PlaceholderCpsAttributionProvider implements CpsAttributionProvider {

    @Override
    public String providerCode() {
        return "PLACEHOLDER_CPS_AFFILIATE";
    }

    @Override
    public CpsLinkResult buildTrackingLink(
            String partner,
            String category,
            String ownerId,
            String planId,
            String offerId,
            BigDecimal amount
    ) {
        String safePartner = (partner == null || partner.isBlank()) ? "roamly_alliance_placeholder" : partner.trim();
        String safeCategory = (category == null || category.isBlank()) ? "OTHER" : category.trim().toUpperCase();
        BigDecimal rate = commissionRateFor(safeCategory);
        BigDecimal safeAmount = amount == null ? BigDecimal.ZERO : amount.max(BigDecimal.ZERO);
        BigDecimal commission = safeAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        String code = "cps_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String url = "https://cps-placeholder.roamly.local/redirect"
                + "?partner=" + enc(safePartner)
                + "&category=" + enc(safeCategory)
                + "&subId=" + enc(code)
                + "&planId=" + enc(planId == null ? "" : planId)
                + "&offerId=" + enc(offerId == null ? "" : offerId);

        return new CpsLinkResult(safePartner, code, url, rate, commission, providerCode());
    }

    private BigDecimal commissionRateFor(String category) {
        return switch (category) {
            case "HOTEL" -> new BigDecimal("0.060");
            case "TICKET" -> new BigDecimal("0.050");
            case "FLIGHT" -> new BigDecimal("0.012");
            case "HIGH_SPEED_RAIL" -> new BigDecimal("0.008");
            default -> new BigDecimal("0.030");
        };
    }

    private String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
