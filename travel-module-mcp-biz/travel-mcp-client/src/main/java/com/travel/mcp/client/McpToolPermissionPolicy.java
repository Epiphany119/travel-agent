package com.travel.mcp.client;

import java.util.Map;
import java.util.Set;

/**
 * MCP 工具最小权限策略。
 *
 * <p>工具名称来自外部配置、模型或未来的编排扩展时，默认拒绝未知工具。当前应用
 * 只允许旅行规划所需的只读查询，任何购买、下单、支付或未登记工具都不能通过
 * {@link McpSession} 发出请求。</p>
 */
public final class McpToolPermissionPolicy {

    private static final Map<String, Set<String>> ALLOWED_TOOLS = Map.of(
            "weather", Set.of("weather.get_forecast", "weather.get_current"),
            "poi", Set.of("poi.geocode", "poi.regeo", "poi.search", "poi.inputtips",
                    "poi.route_walking", "poi.route_transit", "poi.route_driving",
                    "poi.route_bicycling", "poi.distance"),
            "meal", Set.of("meal.search", "meal.get_detail"),
            "budget", Set.of("budget.estimate", "budget.calculate")
    );

    private McpToolPermissionPolicy() {
    }

    public static boolean isAllowed(String serverName, String toolName) {
        return serverName != null && toolName != null
                && ALLOWED_TOOLS.getOrDefault(serverName, Set.of()).contains(toolName);
    }
}
