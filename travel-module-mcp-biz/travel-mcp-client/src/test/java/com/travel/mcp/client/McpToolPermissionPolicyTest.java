package com.travel.mcp.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolPermissionPolicyTest {

    @Test
    void allowsOnlyRegisteredReadOnlyTools() {
        assertTrue(McpToolPermissionPolicy.isAllowed("weather", "weather.get_forecast"));
        assertTrue(McpToolPermissionPolicy.isAllowed("poi", "poi.search"));
        assertTrue(McpToolPermissionPolicy.isAllowed("budget", "budget.estimate"));
    }

    @Test
    void rejectsUnknownOrSideEffectingToolsByDefault() {
        assertFalse(McpToolPermissionPolicy.isAllowed("poi", "order.create"));
        assertFalse(McpToolPermissionPolicy.isAllowed("meal", "package.buy"));
        assertFalse(McpToolPermissionPolicy.isAllowed("unknown", "weather.get_forecast"));
        assertFalse(McpToolPermissionPolicy.isAllowed("weather", null));
    }
}
