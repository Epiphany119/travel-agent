package com.travel.commerce.provider;

import java.math.BigDecimal;

/**
 * 联盟 CPS 归因与追踪链接提供者接口。
 * 第三阶段由占位实现生成标准化追踪链接与分佣测算，后续直接对接携程/飞猪/美团/Booking 联盟 API。
 */
public interface CpsAttributionProvider {

    String providerCode();

    CpsLinkResult buildTrackingLink(
            String partner,
            String category,
            String ownerId,
            String planId,
            String offerId,
            BigDecimal amount
    );

    record CpsLinkResult(
            String partner,
            String attributionCode,
            String trackingUrl,
            BigDecimal commissionRate,
            BigDecimal estimatedCommission,
            String dataSource
    ) {}
}
