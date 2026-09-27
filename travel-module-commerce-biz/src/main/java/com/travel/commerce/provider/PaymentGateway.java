package com.travel.commerce.provider;

import java.math.BigDecimal;

/** 支付供应商端口。生产环境由微信/支付宝/联盟供应商适配器实现。 */
public interface PaymentGateway {
    PaymentResult pay(String orderNo, BigDecimal amount, String currency);

    record PaymentResult(boolean success, String eventId, String providerCode, String message) { }
}
