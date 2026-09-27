package com.travel.commerce.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 本地验收用支付适配器。它只产生可追踪的沙箱支付事件，不代表真实资金扣款。
 */
@Component
public class SandboxPaymentGateway implements PaymentGateway {
    private final boolean enabled;

    public SandboxPaymentGateway(@Value("${travel.commerce.sandbox-enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public PaymentResult pay(String orderNo, BigDecimal amount, String currency) {
        if (!enabled) return new PaymentResult(false, "", "sandbox", "sandbox payment is disabled");
        if (amount == null || amount.signum() < 0) return new PaymentResult(false, "", "sandbox", "invalid amount");
        return new PaymentResult(true, "sandbox_" + UUID.randomUUID().toString().replace("-", ""), "sandbox", "accepted");
    }
}
