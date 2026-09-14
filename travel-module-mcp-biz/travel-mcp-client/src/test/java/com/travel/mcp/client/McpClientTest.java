package com.travel.mcp.client;

import com.travel.mcp.protocol.dto.McpServerInfo;
import com.travel.mcp.protocol.dto.McpToolCall;
import com.travel.mcp.protocol.dto.McpToolResult;
import com.travel.mcp.protocol.jsonrpc.JsonRpcCodec;
import com.travel.mcp.protocol.jsonrpc.JsonRpcRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP Client 单元测试。
 * 
 * <p>使用 EchoMcpServer 验证客户端协议实现。</p>
 */
class McpClientTest {

    private EchoMcpServer echoServer;
    private int port = 9999;

    @BeforeEach
    void setup() throws IOException {
        echoServer = new EchoMcpServer(port);
        echoServer.start();
    }

    @AfterEach
    void teardown() {
        echoServer.stop();
    }

    @Test
    void shouldCallToolAndGetResult() {
        // Test that WebClient can talk to echo server
        String url = echoServer.getUrl();
        String response = WebClient.create(url)
            .post()
            .uri("/mcp/call")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{}}")
            .retrieve()
            .bodyToMono(String.class)
            .block(Duration.ofSeconds(5));

        assertNotNull(response);
        assertTrue(response.contains("\"success\": true"));
        assertTrue(response.contains("杭州"));
    }

    @Test
    void shouldGetServerInfo() {
        // Test server info endpoint
        String url = echoServer.getUrl();
        String response = WebClient.create(url)
            .get()
            .uri("/mcp/info")
            .retrieve()
            .bodyToMono(String.class)
            .block(Duration.ofSeconds(5));

        assertNotNull(response);
        assertTrue(response.contains("\"name\": \"echo-weather\""));
        assertTrue(response.contains("\"version\": \"1.0.0\""));
        assertTrue(response.contains("weather.get_forecast"));
    }

    @Test
    void shouldEncodeAndDecodeJsonRpcRequest() throws Exception {
        // Test JSON-RPC codec
        JsonRpcRequest req = JsonRpcRequest.call("123", "tools/call", 
            Map.of("name", "weather.get_forecast", "arguments", Map.of("city", "北京")));

        String json = JsonRpcCodec.encode(req);
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"method\":\"tools/call\""));
        assertTrue(json.contains("\"name\":\"weather.get_forecast\""));
    }

    @Test
    void shouldCreateMcpToolCall() {
        // Test tool call creation
        Map<String, Object> args = Map.of("city", "上海");
        McpToolCall toolCall = McpToolCall.of("weather.get_forecast", args);

        assertEquals("weather.get_forecast", toolCall.name());
        assertEquals("上海", toolCall.arguments().get("city"));
    }

    @Test
    void shouldCreateMcpToolResult() {
        // Test tool result creation
        Map<String, Object> data = Map.of("city", "深圳", "temp", 30);
        McpToolResult result = McpToolResult.success("weather.get_forecast", data);

        assertTrue(result.success());
        assertEquals("weather.get_forecast", result.name());
        assertNotNull(result.result());
        assertNull(result.error());
    }

    @Test
    void shouldCreateFailedMcpToolResult() {
        // Test failed tool result
        McpToolResult result = McpToolResult.failure("weather.get_forecast", "City not found");

        assertFalse(result.success());
        assertEquals("City not found", result.error());
        assertNull(result.result());
    }

    @Test
    void shouldSubscribeToSseStream() {
        // Test SSE subscription
        String url = echoServer.getUrl();
        String taskId = "test-task-123";

        String events = WebClient.create(url)
            .get()
            .uri("/mcp/stream/" + taskId)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .retrieve()
            .bodyToMono(String.class)
            .block(Duration.ofSeconds(5));

        assertNotNull(events);
        // SSE events contain event type and data
        assertTrue(events.contains("token") || events.contains("tool_call") || events.contains("final"));
    }
}
