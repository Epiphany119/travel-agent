package com.travel.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.service.QuestionnaireService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 问卷式旅行规划 Agent 控制器。
 *
 * <p>提供：
 * <ul>
 *   <li>{@code POST /api/agent/questionnaire/start} - 创建会话并返回首问题</li>
 *   <li>{@code POST /api/agent/questionnaire/{sessionId}/answer?step=N} - 提交回答，SSE 流式返回
 *       （事件: parsed / tool_call / tool_result / next_question / plan / error）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/questionnaire")
public class AgentQuestionnaireController {

    private static final long SSE_TIMEOUT = 120_000L;

    private final QuestionnaireService questionnaireService;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor;

    public AgentQuestionnaireController(QuestionnaireService questionnaireService,
                                        ObjectMapper objectMapper,
                                        @Qualifier("questionnaireExecutor") ExecutorService executor) {
        this.questionnaireService = questionnaireService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /**
     * 创建会话
     */
    @PostMapping("/start")
    public Object start(@RequestBody(required = false) Map<String, String> body) {
        String userId = body != null ? body.get("userId") : null;
        return questionnaireService.startSession(userId);
    }

    /**
     * 提交回答（SSE 流式返回下一问题或最终计划）
     *
     * <p>注意：handleAnswer 必须在异步线程中执行，因为 SseEmitter 需要在方法返回后
     * 由 Spring MVC 发送 HTTP 响应头。如果同步调用，emitter.send() 会在响应头发送前
     * 就被调用，导致事件数据丢失。</p>
     */
    /**
     * 健康检查：测试 ChatModel (智谱 LLM) 是否可调用
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return questionnaireService.healthCheck();
    }

    @PostMapping(value = "/{sessionId}/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter answer(@PathVariable String sessionId,
                             @RequestParam int step,
                             @RequestParam String answer) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        final String safeAnswer = answer == null ? "" : answer;
        log.info("问卷问答: sessionId={}, step={}, answer(raw) length={}", sessionId, step, safeAnswer.length());

        // 注册超时和完成回调
        emitter.onTimeout(() -> {
            log.warn("SSE 超时: sessionId={}", sessionId);
            emitter.complete();
        });
        emitter.onCompletion(() -> log.info("SSE 完成: sessionId={}", sessionId));
        emitter.onError(t -> log.error("SSE 错误: sessionId={}", sessionId, t));

        // 异步执行，避免阻塞 SSE 响应
        try {
            executor.execute(() -> {
                try {
                    questionnaireService.handleAnswer(sessionId, step, safeAnswer, emitter);
                } catch (Exception e) {
                    log.error("问卷问答处理异常: sessionId={}, step={}", sessionId, step, e);
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(objectMapper.writeValueAsString(Map.of("message", "服务器内部错误"))));
                    } catch (Exception ignored) {
                    }
                    emitter.complete();
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("问卷执行器已满载，快速拒绝请求: sessionId={}, step={}", sessionId, step);
            emitter.completeWithError(new IllegalStateException("服务繁忙，请稍后重试"));
        }

        return emitter;
    }
}
