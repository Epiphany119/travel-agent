package com.travel.a2a.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.mcp.protocol.a2a.A2AStreamEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Short-lived Redis event history for A2A SSE replay. Durable replay remains backed by saved plan versions. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEventStore {
    private static final Duration TTL = Duration.ofHours(24);
    private static final int HISTORY_LIMIT = 500;
    private static final long POLL_MILLIS = 200;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final TaskStateStore taskStateStore;

    public long publish(String taskId, String eventName, A2AStreamEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            Long id = redis.opsForValue().increment(sequenceKey(taskId));
            if (id == null) throw new IllegalStateException("Redis did not allocate an event id");
            String encodedPayload = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            redis.opsForList().rightPush(eventsKey(taskId), id + "\t" + eventName + "\t" + encodedPayload);
            redis.opsForList().trim(eventsKey(taskId), -HISTORY_LIMIT, -1);
            redis.expire(eventsKey(taskId), TTL);
            redis.expire(sequenceKey(taskId), TTL);
            return id;
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("淇濆瓨 AI SSE 浜嬩欢澶辫触: taskId={}, event={}, causeType={}",
                    taskId, eventName, e.getClass().getSimpleName());
            return -1;
        }
    }

    public boolean hasEvents(String taskId) {
        try {
            Long size = redis.opsForList().size(eventsKey(taskId));
            return size != null && size > 0;
        } catch (RuntimeException e) {
            log.warn("璇诲彇 AI SSE 浜嬩欢鏁伴噺澶辫触: taskId={}, causeType={}", taskId, e.getClass().getSimpleName());
            return false;
        }
    }

    public boolean reserveReplay(String taskId) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                    replayKey(taskId), "1", Duration.ofSeconds(30)));
        } catch (RuntimeException e) {
            log.warn("鐢宠 AI SSE 缁撴灉閲嶆斁閿佸け璐? taskId={}, causeType={}", taskId, e.getClass().getSimpleName());
            return false;
        }
    }

    @Async("sseStreamExecutor")
    public void stream(String taskId, SseEmitter emitter, long lastEventId) {
        AtomicBoolean active = new AtomicBoolean(true);
        emitter.onCompletion(() -> active.set(false));
        emitter.onTimeout(() -> active.set(false));
        emitter.onError(error -> active.set(false));
        long cursor = Math.max(0, lastEventId);
        try {
            while (active.get()) {
                List<TaskEvent> events = readAfter(taskId, cursor);
                for (TaskEvent event : events) {
                    if (!active.get()) return;
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(event.id()))
                            .name(event.name())
                            .data(event.payload()));
                    cursor = event.id();
                }

                TaskStateStore.TaskState state = taskStateStore.get(taskId);
                if (state == null) {
                    emitter.complete();
                    return;
                }
                boolean terminal = "SUCCEEDED".equals(state.status())
                        || "FAILED".equals(state.status()) || "CANCELLED".equals(state.status());
                if (terminal && events.isEmpty()) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (IOException e) {
            log.debug("AI SSE 瀹㈡埛绔柇寮€: taskId={}", taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (RuntimeException e) {
            log.warn("AI SSE 浜嬩欢杞彂澶辫触: taskId={}, causeType={}", taskId, e.getClass().getSimpleName());
            emitter.completeWithError(e);
        }
    }
    private List<TaskEvent> readAfter(String taskId, long cursor) {
        List<String> rows = redis.opsForList().range(eventsKey(taskId), 0, -1);
        if (rows == null || rows.isEmpty()) return List.of();
        List<TaskEvent> events = new ArrayList<>();
        for (String row : rows) {
            try {
                String[] parts = row.split("\t", 3);
                if (parts.length != 3) continue;
                long id = Long.parseLong(parts[0]);
                if (id <= cursor) continue;
                String payload = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                events.add(new TaskEvent(id, parts[1], payload));
            } catch (RuntimeException e) {
                log.warn("蹇界暐鎹熷潖鐨?AI SSE 浜嬩欢璁板綍: taskId={}", taskId);
            }
        }
        return events;
    }

    private String eventsKey(String taskId) { return "a2a:events:" + taskId; }
    private String sequenceKey(String taskId) { return "a2a:event-seq:" + taskId; }
    private String replayKey(String taskId) { return "a2a:replay:" + taskId; }

    private record TaskEvent(long id, String name, String payload) { }
}