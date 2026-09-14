package com.travel.agent.config;

import com.travel.common.http.HttpClientSupport;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智谱清言 ChatModel。
 *
 * <p>spring-ai 1.0.0-M4 的 {@link OpenAiApi} 把补全路径硬编码为
 * {@code "/v1/chat/completions"}，而智谱的 OpenAI 兼容地址是
 * {@code https://open.bigmodel.cn/api/paas/v4/chat/completions}（没有 /v1）。
 * 若直接配 base-url 为 /api/paas/v4，实际会请求 /v4/v1/chat/completions 从而 404。
 * 这里用 7 参构造器手动覆盖 {@code completionsPath}，让智谱真正可调用。</p>
 */
@Configuration
public class ZhipuChatConfig {

    private static final Logger log = LoggerFactory.getLogger(ZhipuChatConfig.class);

    /**
     * 优先使用的智谱 ChatModel。解密 narrative：不走 autoconfig 默认 bean。
     */
    @Bean
    @Primary
    public ChatModel zhipuChatModel(
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:glm-4-flash}") String model) {

        // 关键诊断：确认 API Key 配置状态
        boolean keyConfigured = apiKey != null && !apiKey.isBlank();
        log.info("========== 智谱 ChatModel 初始化 ==========");
        log.info("API Key 已配置: {}, 长度: {}", keyConfigured, apiKey != null ? apiKey.length() : 0);
        if (keyConfigured) {
            // 只显示前4后4位，中间打码
            String masked = apiKey.length() > 8
                    ? apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4)
                    : "****";
            log.info("API Key (脱敏): {}", masked);
        } else {
            log.warn("!!! API Key 未配置！请设置环境变量 ZHIPU_API_KEY !!!");
        }
        log.info("使用模型: {}", model);
        log.info("============================================");

        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(model);
        options.setTemperature(0.7d);
        // 提高最大输出 token，避免长行程（多天详细计划）被截断
        options.setMaxTokens(4096);

        // 构建带拦截器的 RestClient，用于诊断 Authorization 头是否正确设置
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(HttpClientSupport.newRequestFactory())
                .requestInterceptor((HttpRequest request, byte[] body, ClientHttpRequestExecution execution) -> {
                    String auth = request.getHeaders().getFirst("Authorization");
                    String method = request.getMethod() != null ? request.getMethod().name() : "GET";
                    String uri = request.getURI().toString();
                    if (auth != null && auth.startsWith("Bearer ")) {
                        String token = auth.substring(7);
                        String masked = token.length() > 8 ? token.substring(0, 4) + "****" + token.substring(token.length() - 4) : "****";
                        log.info("HTTP 请求: {} {} | Authorization: Bearer {} | Content-Type: {}",
                                method, uri, masked, request.getHeaders().getFirst("Content-Type"));
                    } else {
                        log.warn("HTTP 请求: {} {} | Authorization: MISSING or INVALID [{}]", method, uri, auth);
                    }
                    return execution.execute(request, body);
                });

        OpenAiApi api = new OpenAiApi(
                "https://open.bigmodel.cn/api/paas/v4",
                apiKey,
                "/chat/completions",
                "/embeddings",
                restClientBuilder,
                WebClient.builder(),
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
        return new OpenAiChatModel(api, options);
    }
}
