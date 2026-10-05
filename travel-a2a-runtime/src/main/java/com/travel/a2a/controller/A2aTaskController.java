package com.travel.a2a.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.a2a.config.A2aRuntimeProperties;
import com.travel.a2a.model.TravelPlanRequest;
import com.travel.a2a.service.HostAgentService;
import com.travel.a2a.service.TaskEventStore;
import com.travel.a2a.service.TaskStateStore;
import com.travel.mcp.protocol.a2a.A2AStreamEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/** A2A tasks are created by POST and only subscribed to by GET. */
@Slf4j
@RestController
@RequestMapping("/a2a/tasks")
@RequiredArgsConstructor
public class A2aTaskController {
    private final HostAgentService hostAgentService;
    private final A2aRuntimeProperties runtimeProperties;
    private final TaskStateStore taskStateStore;
    private final TaskEventStore taskEventStore;
    private final ObjectMapper objectMapper;

    @PostMapping
    public Map<String, String> createTask(@Valid @RequestBody TravelPlanRequest request,
                                          @RequestHeader("Idempotency-Key") String idempotencyKey,
                                          HttpServletRequest http) {
        String ownerId = currentUserId(http);
        String taskId = UUID.randomUUID().toString();
        var creation = taskStateStore.createOrGet(
                taskId, ownerId, "default", idempotencyKey, requestJson(request));
        boolean started = startPlanIfPending(request, creation.taskId());
        var state = taskStateStore.get(creation.taskId());
        String status = started ? "created" : state == null ? "unknown" : state.status().toLowerCase();
        return Map.of("taskId", creation.taskId(), "status", status);
    }

    @GetMapping("/{taskId}/status")
    public Map<String, Object> getTaskStatus(@PathVariable String taskId, HttpServletRequest http) {
        var state = taskStateStore.get(taskId);
        if (state == null) return Map.of("taskId", taskId, "status", "NOT_FOUND");
        requireOwner(state, currentUserId(http));
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("taskId", state.taskId());
        response.put("status", state.status());
        response.put("progress", state.progress());
        response.put("error", state.error() == null ? "" : state.error());
        response.put("updatedAt", state.updatedAt().toString());
        if ("SUCCEEDED".equals(state.status()) && state.planId() != null) {
            response.put("planId", state.planId());
        }
        return response;
    }

    @PostMapping("/{taskId}/cancel")
    public Map<String, String> cancelTask(@PathVariable String taskId, HttpServletRequest http) {
        var state = taskStateStore.get(taskId);
        if (state == null) return Map.of("taskId", taskId, "status", "NOT_FOUND");
        requireOwner(state, currentUserId(http));
        boolean cancelled = taskStateStore.cancel(taskId);
        if (!cancelled) {
            var latest = taskStateStore.get(taskId);
            return Map.of("taskId", taskId,
                    "status", latest == null ? "NOT_FOUND" : latest.status());
        }
        taskEventStore.publish(taskId, "task_update",
                A2AStreamEvent.taskUpdate(Map.of("taskId", taskId, "status", "CANCELLED",
                        "message", "任务已取消")));
        return Map.of("taskId", taskId, "status", "CANCELLED");
    }

    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTask(@PathVariable String taskId,
                                 @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                                 HttpServletRequest http) {
        var state = taskStateStore.get(taskId);
        if (state == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
        requireOwner(state, currentUserId(http));
        long cursor = parseLastEventId(lastEventId);

        if (!taskEventStore.hasEvents(taskId) && isTerminal(state.status()) && taskEventStore.reserveReplay(taskId)) {
            if ("SUCCEEDED".equals(state.status()) && state.planId() != null) {
                hostAgentService.replayCompleted(taskId);
            } else if ("FAILED".equals(state.status())) {
                taskEventStore.publish(taskId, "error",
                        A2AStreamEvent.error(Map.of("message", "任务失败，请重新发起规划")));
            } else if ("CANCELLED".equals(state.status())) {
                taskEventStore.publish(taskId, "task_update",
                        A2AStreamEvent.taskUpdate(Map.of("taskId", taskId, "status", "CANCELLED",
                                "message", "任务已取消")));
            }
            cursor = 0;
        }

        SseEmitter emitter = newEmitter();
        taskEventStore.stream(taskId, emitter, cursor);
        return emitter;
    }

    private boolean startPlanIfPending(TravelPlanRequest request, String taskId) {
        int attempt = taskStateStore.claimExecutionAttempt(taskId);
        if (attempt < 0) return false;
        String traceId = MDC.get("traceId");
        try {
            hostAgentService.plan(request, taskId, traceId, attempt);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("A2A 执行器已满载，拒绝任务: taskId={}", taskId);
            taskStateStore.fail(taskId, "服务繁忙，请稍后重试", attempt);
            taskEventStore.publish(taskId, "error",
                    A2AStreamEvent.error(Map.of("message", "服务繁忙，请稍后重试")));
            return false;
        }
    }

    private SseEmitter newEmitter() {
        Duration timeout = runtimeProperties.getSseTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) timeout = Duration.ofMinutes(5);
        return new SseEmitter(timeout.toMillis());
    }

    private long parseLastEventId(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            long id = Long.parseLong(value);
            if (id < 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Last-Event-ID 格式无效");
        }
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private String currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute("authenticatedUserId");
        if (value == null || value.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return value.toString();
    }

    private void requireOwner(TaskStateStore.TaskState state, String ownerId) {
        if (state.ownerId() == null || state.ownerId().isBlank() || !state.ownerId().equals(ownerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在或无权访问");
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
