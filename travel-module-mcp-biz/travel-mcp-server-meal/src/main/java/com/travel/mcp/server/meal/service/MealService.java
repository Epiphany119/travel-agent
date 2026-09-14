package com.travel.mcp.server.meal.service;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MealService {

    private static final String RESTAURANT_TYPE = "餐饮服务";
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(7);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    @Value("${travel.meal.poi-server-url}")
    private String poiServerUrl;

    private final WebClient webClient;

    public MealService() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)
                .responseTimeout(RESPONSE_TIMEOUT)
                .doOnConnected(connection -> connection
                        .addHandlerLast(new WriteTimeoutHandler(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)));
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @SuppressWarnings("unchecked")
    public MealSearchResult search(String keywords, String city, Integer limit) {
        if (keywords == null || keywords.isBlank()) {
            return MealSearchResult.failure("搜索关键词不能为空");
        }
        if (city == null || city.isBlank()) {
            return MealSearchResult.failure("城市不能为空");
        }

        try {
            String requestBody = String.format(
                "{\n" +
                "    \"jsonrpc\": \"2.0\",\n" +
                "    \"id\": 1,\n" +
                "    \"method\": \"tools/call\",\n" +
                "    \"params\": {\n" +
                "        \"name\": \"poi.search\",\n" +
                "        \"arguments\": {\n" +
                "            \"keywords\": \"%s\",\n" +
                "            \"city\": \"%s\",\n" +
                "            \"types\": \"%s\",\n" +
                "            \"limit\": %d\n" +
                "        }\n" +
                "    }\n" +
                "}",
                keywords, city, RESTAURANT_TYPE, limit != null ? limit : 10);

            String response = webClient.post()
                    .uri(poiServerUrl + "/mcp/call")
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(REQUEST_TIMEOUT)
                    .block();

            log.info("POI Server response: {}", response);

            if (response == null || response.isBlank()) {
                return MealSearchResult.failure("POI服务返回空响应");
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> rpcResponse = mapper.readValue(response, Map.class);

            Object result = rpcResponse.get("result");
            if (result == null) {
                Object error = rpcResponse.get("error");
                if (error instanceof Map) {
                    return MealSearchResult.failure((String) ((Map<String, Object>) error).get("message"));
                }
                return MealSearchResult.failure("POI服务调用失败");
            }

            Map<String, Object> toolResult = (Map<String, Object>) result;
            Boolean success = (Boolean) toolResult.get("success");
            if (success == null || !success) {
                return MealSearchResult.failure((String) toolResult.get("error"));
            }

            Map<String, Object> poiData = (Map<String, Object>) toolResult.get("result");
            return MealSearchResult.success(poiData);

        } catch (Exception e) {
            log.error("餐饮搜索失败: {}", e.getMessage(), e);
            return MealSearchResult.failure("餐饮搜索失败: " + e.getMessage());
        }
    }

    public static class MealSearchResult {
        private boolean success;
        private String error;
        private Object data;

        public static MealSearchResult success(Object data) {
            MealSearchResult result = new MealSearchResult();
            result.success = true;
            result.data = data;
            return result;
        }

        public static MealSearchResult failure(String error) {
            MealSearchResult result = new MealSearchResult();
            result.success = false;
            result.error = error;
            return result;
        }

        public boolean isSuccess() { return success; }
        public String getError() { return error; }
        public Object getData() { return data; }
    }
}
