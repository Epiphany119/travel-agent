package com.travel.a2a.controller;

import com.travel.a2a.config.A2aRuntimeProperties;
import com.travel.a2a.model.TravelPlanRequest;
import com.travel.a2a.service.HostAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
@CrossOrigin(origins = "*")
public class A2aTaskController {

    private final HostAgentService hostAgentService;
    private final A2aRuntimeProperties runtimeProperties;

    /**
     * 创建新任务并开始执行
     * 
     * GET /a2a/tasks/stream - 创建新任务并返回SSE流
     *
     * @param request 行程请求
     * @return SSE流
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createAndStream(@ModelAttribute TravelPlanRequest request) {
        String taskId = UUID.randomUUID().toString();
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
    public Map<String, Object> getTaskStatus(@PathVariable String taskId) {
        log.info("查询任务状态: taskId={}", taskId);
        // TODO: 可以通过Redis或其他方式存储任务状态
        return Map.of(
                "taskId", taskId,
                "status", "processing"
        );
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
    public Map<String, String> createTask(@RequestBody TravelPlanRequest request) {
        String taskId = UUID.randomUUID().toString();
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
                                  @RequestBody(required = false) TravelPlanRequest request) {
        log.info("获取任务SSE流: taskId={}", taskId);

        if (request == null) {
            request = new TravelPlanRequest();
            request.setDestination("北京");
            request.setDays(3);
            request.setBudget(5000);
            request.setTravelers(2);
            request.setTravelStyle("休闲");
        }

        SseEmitter emitter = newEmitter();
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
}
