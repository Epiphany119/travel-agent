package com.travel.mcp.client;

import com.travel.mcp.protocol.a2a.A2AStreamEvent;
import com.travel.mcp.protocol.dto.McpServerInfo;
import com.travel.mcp.protocol.dto.McpTool;
import com.travel.mcp.protocol.dto.McpToolCall;
import com.travel.mcp.protocol.dto.McpToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * 单个 MCP Server 的会话封装。
 * 
 * <p>提供工具调用接口和错误处理逻辑。重试不在会话层自动执行，避免超时请求被放大；
 * 如需重试，应由上层针对幂等且可恢复的错误，在总截止时间内显式控制。
 * 实例由 {@link McpClient} 的 {@code @Bean} 方法统一创建，请勿直接使用 {@code @Component} 注入。</p>
 */
public class McpSession {

    private static final Logger log = LoggerFactory.getLogger(McpSession.class);

    /**
     * 服务器名称，如 "weather", "poi"
     */
    private final String serverName;

    /**
     * 服务器 URL
     */
    private final String serverUrl;

    /**
     * 传输层
     */
    private final McpTransport transport;

    /**
     * 服务器信息
     */
    private final McpServerInfo serverInfo;

    public McpSession(String serverName, String serverUrl, McpTransport transport, McpServerInfo serverInfo) {
        this.serverName = serverName;
        this.serverUrl = serverUrl;
        this.transport = transport;
        this.serverInfo = serverInfo;
        log.info("Initialized MCP session: name={}, url={}, tools={}",
            serverName, serverUrl, serverInfo.tools().size());
    }

    /**
     * 调用工具并返回结果。
     *
     * @param toolCall 工具调用请求
     * @return 工具执行结果
     */
    public McpToolResult callTool(McpToolCall toolCall) {
        if (toolCall == null || !McpToolPermissionPolicy.isAllowed(serverName, toolCall.name())) {
            String toolName = toolCall == null ? "unknown" : toolCall.name();
            log.warn("Tool call rejected by permission policy: server={}, tool={}", serverName, toolName);
            return McpToolResult.failure(toolName, "工具未被授权");
        }
        log.info("Calling tool: server={}, tool={}", serverName, toolCall.name());
        try {
            McpToolResult result = transport.callTool(serverUrl, toolCall);
            if (result.success()) {
                log.info("Tool call succeeded: server={}, tool={}", serverName, toolCall.name());
            } else {
                log.warn("Tool call failed: server={}, tool={}, error={}",
                    serverName, toolCall.name(), result.error());
            }
            return result;
        } catch (Exception e) {
            String message = isTimeout(e) ? "MCP 请求超时" : "MCP 工具调用失败";
            log.warn("Tool call error: server={}, tool={}, reason={}",
                    serverName, toolCall.name(), message);
            return McpToolResult.failure(toolCall.name(), message);
        }
    }

    /**
     * 订阅流式响应。
     *
     * @param taskId 任务 ID
     * @return SSE 事件流
     */
    public Flux<A2AStreamEvent> subscribeStream(String taskId) {
        log.info("Subscribing to stream: server={}, taskId={}", serverName, taskId);
        return transport.subscribeSse(serverUrl, taskId);
    }

    /**
     * 获取服务器支持的工具列表。
     *
     * @return 工具列表
     */
    public List<McpTool> getTools() {
        return serverInfo.tools().stream()
                .filter(tool -> tool != null
                        && McpToolPermissionPolicy.isAllowed(serverName, tool.name()))
                .toList();
    }

    /**
     * 获取服务器名称。
     *
     * @return 服务器名称
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * 获取服务器 URL。
     *
     * @return 服务器 URL
     */
    public String getServerUrl() {
        return serverUrl;
    }

    /**
     * 获取服务器信息。
     *
     * @return 服务器信息
     */
    public McpServerInfo getServerInfo() {
        return serverInfo;
    }

    /**
     * 调用指定工具的便捷方法。
     *
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return 工具执行结果
     */
    public McpToolResult callTool(String toolName, java.util.Map<String, Object> arguments) {
        return callTool(new McpToolCall(toolName, arguments));
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
