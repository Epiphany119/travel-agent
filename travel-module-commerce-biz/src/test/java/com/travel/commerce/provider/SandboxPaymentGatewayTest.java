package com.travel.commerce.provider;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class SandboxPaymentGatewayTest {

    @Test
    void acceptsNonNegativeAmountWhenEnabled() {
        SandboxPaymentGateway gateway = new SandboxPaymentGateway(true);

        PaymentGateway.PaymentResult result = gateway.pay("ord_1", new BigDecimal("199.00"), "CNY");

        assertTrue(result.success());
        assertTrue(result.eventId().startsWith("sandbox_"));
        assertEquals("sandbox", result.providerCode());
        assertEquals("accepted", result.message());
    }

    @Test
    void rejectsNegativeAmountAndDisabledGateway() {
        SandboxPaymentGateway enabled = new SandboxPaymentGateway(true);
        PaymentGateway.PaymentResult invalid = enabled.pay("ord_1", new BigDecimal("-1"), "CNY");
        assertFalse(invalid.success());
        assertEquals("invalid amount", invalid.message());

        SandboxPaymentGateway disabled = new SandboxPaymentGateway(false);
        PaymentGateway.PaymentResult unavailable = disabled.pay("ord_1", BigDecimal.ONE, "CNY");
        assertFalse(unavailable.success());
        assertEquals("sandbox payment is disabled", unavailable.message());
    }
}
