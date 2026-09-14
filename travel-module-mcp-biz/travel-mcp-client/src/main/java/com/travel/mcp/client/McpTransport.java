package com.travel.mcp.client;

import com.travel.mcp.client.config.McpClientConfig;
import com.travel.mcp.protocol.a2a.A2AStreamEvent;
import com.travel.mcp.protocol.dto.McpServerInfo;
import com.travel.mcp.protocol.dto.McpToolCall;
import com.travel.mcp.protocol.dto.McpToolResult;
import com.travel.mcp.protocol.jsonrpc.JsonRpcCodec;
import com.travel.mcp.protocol.jsonrpc.JsonRpcErrorCodes;
import com.travel.mcp.protocol.jsonrpc.JsonRpcRequest;
import com.travel.mcp.protocol.jsonrpc.JsonRpcResponse;
import com.travel.mcp.protocol.jsonrpc.McpProtocolException;
import com.travel.mcp.protocol.util.JsonUtil;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MCP 传输层。
 * 
 * <p>负责与 MCP Server 的 HTTP 通信，支持同步调用和 SSE 流订阅。</p>
 */
@Component
public class McpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpTransport.class);

    private final WebClient webClient;
    private final WebClient streamingWebClient;
    private final Duration requestTimeout;
    private final Duration serverInfoTimeout;

    /**
     * 创建带分层超时的 MCP HTTP 客户端。
     *
     * <p>普通请求使用连接、响应读取、应用层三层超时；SSE 使用独立客户端，
     * 不设置读空闲超时，避免服务端暂时没有事件时误断开长连接。</p>
     */
    @Autowired
    public McpTransport(WebClient.Builder webClientBuilder, McpClientConfig config) {
        Duration connectTimeout = positive(config.getConnectTimeout(), Duration.ofSeconds(3));
        Duration responseTimeout = positive(config.getResponseTimeout(), Duration.ofSeconds(8));
        Duration configuredRequestTimeout = positive(config.getRequestTimeout(), Duration.ofSeconds(9));
        // 应用层超时要晚于网络读超时，避免同一请求由两个相同计时器同时终止。
        this.requestTimeout = configuredRequestTimeout.compareTo(responseTimeout) > 0
                ? configuredRequestTimeout
                : responseTimeout.plusSeconds(1);
        this.serverInfoTimeout = positive(config.getServerInfoTimeout(), Duration.ofSeconds(3));

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, toIntMillis(connectTimeout))
                .responseTimeout(responseTimeout)
                .doOnConnected(connection -> connection
                        .addHandlerLast(new WriteTimeoutHandler(responseTimeout.toMillis(), TimeUnit.MILLISECONDS)));

        // SSE 连接只限制建连时间，不限制连接建立后的读空闲时间。
        HttpClient streamingHttpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, toIntMillis(connectTimeout));

        this.webClient = buildClient(webClientBuilder.clone(), httpClient);
        this.streamingWebClient = buildClient(webClientBuilder.clone(), streamingHttpClient);
    }

    /**
     * 保留一个参数构造器，方便非 Spring 场景和轻量单元测试直接创建传输层。
     */
    public McpTransport(WebClient.Builder webClientBuilder) {
        this(webClientBuilder, new McpClientConfig());
    }

    private WebClient buildClient(WebClient.Builder builder, HttpClient httpClient) {
        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /**
     * 发送 JSON-RPC 请求到指定 URL
     *
     * @param serverUrl 服务器 URL
     * @param method 方法名
     * @param params 参数
     * @return JSON 响应字符串
     */
    private String sendRequest(String serverUrl, String method, Map<String, Object> params) {
        String requestId = UUID.randomUUID().toString();
        JsonRpcRequest req = JsonRpcRequest.call(requestId, method, params);

        log.debug("Sending JSON-RPC request to {}: method={}, id={}", serverUrl, method, requestId);

        return webClient.post()
            .uri(serverUrl + "/mcp/call")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(requestTimeout)
            .block();
    }

    /**
     * 调用 MCP 工具并获取结果。
     * 
     * <p>用于非流式调用，同步返回工具执行结果。</p>
     *
     * @param serverUrl MCP Server URL
     * @param toolCall 工具调用请求
     * @return 工具执行结果
     */
    public McpToolResult callTool(String serverUrl, McpToolCall toolCall) {
        String response = sendRequest(serverUrl, "tools/call",
            Map.of("name", toolCall.name(), "arguments", toolCall.arguments()));

        log.debug("Received response from {}: {}", serverUrl, response);

        JsonRpcResponse rpcResp;
        try {
            rpcResp = JsonRpcCodec.decodeResponse(response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new McpProtocolException("Failed to decode JSON-RPC response: " + e.getMessage(),
                JsonRpcErrorCodes.INTERNAL_ERROR, e);
        }

        Map<String, Object> result = (Map<String, Object>) rpcResp.getResultOrThrow();

        return new McpToolResult(
            toolCall.name(),
            (Boolean) result.getOrDefault("success", true),
            result.get("result"),
            (String) result.get("error")
        );
    }

    /**
     * 订阅 MCP Server 的 SSE 流。
     * 
     * <p>用于流式响应场景，返回 A2AStreamEvent 的 Flux。</p>
     *
     * @param serverUrl MCP Server URL
     * @param taskId 任务 ID
     * @return SSE 事件流
     */
    public Flux<A2AStreamEvent> subscribeSse(String serverUrl, String taskId) {
        log.info("Subscribing to SSE stream: server={}, taskId={}", serverUrl, taskId);

        return streamingWebClient.get()
            .uri(serverUrl + "/mcp/stream/" + taskId)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .retrieve()
            .bodyToFlux(String.class)
            .filter(line -> !line.isEmpty() && line.startsWith("data: "))
            .map(line -> line.substring(6))
            .map(data -> {
                try {
                    return JsonUtil.parseA2AStreamEvent(data);
                } catch (Exception e) {
                    log.warn("Failed to parse SSE data: {}", data, e);
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .doOnError(e -> log.error("SSE stream error", e))
            .doOnComplete(() -> log.info("SSE stream completed: taskId={}", taskId));
    }

    /**
     * 获取 MCP Server 信息。
     * 
     * <p>包括服务器名称、版本和支持的工具列表。</p>
     *
     * @param serverUrl MCP Server URL
     * @return 服务器信息
     */
    public McpServerInfo getServerInfo(String serverUrl) {
        String response = webClient.get()
            .uri(serverUrl + "/mcp/info")
            .retrieve()
            .bodyToMono(String.class)
            .timeout(serverInfoTimeout)
            .block();

        log.debug("Received server info from {}: {}", serverUrl, response);

        JsonRpcResponse rpcResp;
        try {
            rpcResp = JsonRpcCodec.decodeResponse(response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new McpProtocolException("Failed to decode server info response: " + e.getMessage(),
                JsonRpcErrorCodes.INTERNAL_ERROR, e);
        }

        return JsonUtil.parseServerInfo(rpcResp.getResultOrThrow());
    }

    /**
     * 发送请求并获取响应（通用方法）
     *
     * @param serverUrl 服务器 URL
     * @param path 路径
     * @param method HTTP 方法
     * @param body 请求体
     * @return 响应字符串
     */
    public String sendRawRequest(String serverUrl, String path, String method, Object body) {
        WebClient.RequestBodySpec spec = webClient.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(serverUrl + path);

        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        }

        return spec.retrieve().bodyToMono(String.class).timeout(requestTimeout).block();
    }

    private static Duration positive(Duration candidate, Duration fallback) {
        return candidate == null || candidate.isZero() || candidate.isNegative() ? fallback : candidate;
    }

    private static int toIntMillis(Duration duration) {
        long millis = duration.toMillis();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, millis));
    }
}
