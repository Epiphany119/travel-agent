package com.travel.a2a.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.a2a.config.A2aRuntimeProperties;
import com.travel.a2a.model.TravelPlanRequest;
import com.travel.a2a.service.HostAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;

/**
 * A2A任务控制器
 * 
 * <p>提供HTTP端点，通过SSE流输出A2A事件。</p>
 */
@Slf4j
@RestController
@RequestMapping("/a2a/tasks")
@RequiredArgsConstructor
public class A2aTaskController {

    private final HostAgentService hostAgentService;
    private final A2aRuntimeProperties runtimeProperties;
    private final com.travel.a2a.service.TaskStateStore taskStateStore;
    private final ObjectMapper objectMapper;

    /**
     * 创建新任务并开始执行
     * 
     * GET /a2a/tasks/stream - 创建新任务并返回SSE流
     *
     * @param request 行程请求
     * @return SSE流
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createAndStream(@Valid @ModelAttribute TravelPlanRequest request, HttpServletRequest http) {
        String ownerId = currentUserId(http);
        String taskId = UUID.randomUUID().toString();
        taskStateStore.createOrGet(taskId, ownerId, "default", null, requestJson(request));
        log.info("创建新任务并开始SSE流: taskId={}, destination={}, days={}",
                taskId, request.getDestination(), request.getDays());

        SseEmitter emitter = newEmitter();
        startPlan(request, taskId, emitter);
        return emitter;
    }

    /**
     * 获取指定任务的状态
     * 
     * GET /a2a/tasks/{taskId}/status - 获取任务状态
     *
     * @param taskId 任务ID
     * @return 任务状态
     */
    @GetMapping("/{taskId}/status")
    public Map<String, Object> getTaskStatus(@PathVariable String taskId, HttpServletRequest http) {
        log.info("查询任务状态: taskId={}", taskId);
        var state = taskStateStore.get(taskId);
        if (state == null) return Map.of("taskId", taskId, "status", "NOT_FOUND");
        requireOwner(state, currentUserId(http));
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("taskId", state.taskId());
        response.put("status", state.status());
        response.put("progress", state.progress());
        response.put("error", state.error() == null ? "" : state.error());
        response.put("updatedAt", state.updatedAt().toString());
        if (state.planId() != null) response.put("planId", state.planId());
        return response;
    }

    @PostMapping("/{taskId}/cancel")
    public Map<String, String> cancelTask(@PathVariable String taskId, HttpServletRequest http) {
        var state = taskStateStore.get(taskId);
        if (state == null) return Map.of("taskId", taskId, "status", "NOT_FOUND");
        requireOwner(state, currentUserId(http));
        taskStateStore.cancel(taskId); return Map.of("taskId", taskId, "status", "CANCELLED");
    }

    /**
     * POST端点 - 创建新任务（REST风格）
     * 
     * POST /a2a/tasks - 创建新任务
     *
     * @param request 行程请求
     * @return 任务ID
     */
    @PostMapping
    public Map<String, String> createTask(@Valid @RequestBody TravelPlanRequest request,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                          HttpServletRequest http) {
        String ownerId = currentUserId(http);
        String taskId = UUID.randomUUID().toString();
        var creation = taskStateStore.createOrGet(taskId, ownerId, "default", idempotencyKey, requestJson(request));
        if (!creation.created()) {
            var existing = taskStateStore.get(creation.taskId());
            return Map.of("taskId", creation.taskId(), "status", existing == null ? "existing" : existing.status().toLowerCase());
        }
        log.info("创建新任务: taskId={}, destination={}, days={}",
                taskId, request.getDestination(), request.getDays());

        // 启动异步任务
        SseEmitter emitter = newEmitter();
        return startPlan(request, taskId, emitter)
                ? Map.of("taskId", taskId, "status", "created")
                : Map.of("taskId", taskId, "status", "rejected");
    }

    /**
     * 获取指定任务的SSE流
     * 
     * GET /a2a/tasks/{taskId}/stream - 获取任务SSE流
     *
     * @param taskId 任务ID
     * @param request 行程请求（可选）
     * @return SSE流
     */
    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTask(@PathVariable String taskId,
                                  @RequestBody(required = false) TravelPlanRequest request,
                                  HttpServletRequest http) {
        log.info("获取任务SSE流: taskId={}", taskId);
        var state = taskStateStore.get(taskId);
        if (state == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "任务不存在");
        requireOwner(state, currentUserId(http));

        if (request == null) {
            request = new TravelPlanRequest();
            request.setDestination("北京");
            request.setDays(3);
            request.setBudget(5000);
            request.setTravelers(2);
            request.setTravelStyle("休闲");
        }

        SseEmitter emitter = newEmitter();
        var current = taskStateStore.get(taskId);
        if (current != null && ("SUCCEEDED".equals(current.status()) || "FAILED".equals(current.status()) || "CANCELLED".equals(current.status()))) {
            hostAgentService.replay(taskId, emitter);
            return emitter;
        }
        startPlan(request, taskId, emitter);
        return emitter;
    }

    private boolean startPlan(TravelPlanRequest request, String taskId, SseEmitter emitter) {
        try {
            hostAgentService.plan(request, taskId, emitter);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("A2A 执行器已满载，拒绝任务: taskId={}", taskId);
            emitter.completeWithError(new IllegalStateException("服务繁忙，请稍后重试"));
            return false;
        }
    }

    private SseEmitter newEmitter() {
        Duration timeout = runtimeProperties.getSseTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofMinutes(5);
        }
        return new SseEmitter(timeout.toMillis());
    }

    private String currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute("authenticatedUserId");
        if (value == null || value.toString().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return value.toString();
    }

    private void requireOwner(com.travel.a2a.service.TaskStateStore.TaskState state, String ownerId) {
        if (state.ownerId() == null || state.ownerId().isBlank() || !state.ownerId().equals(ownerId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "无权访问该任务");
        }
    }

    private String requestJson(TravelPlanRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法保存任务请求", e);
        }
    }
}
