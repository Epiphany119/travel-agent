package com.travel.a2a.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.a2a.model.TravelPlanResult;
import com.travel.mcp.protocol.a2a.A2AStreamEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Short-lived Redis event history and atomic terminal-event writer for A2A SSE replay. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEventStore {
    private static final Duration TTL = Duration.ofHours(24);
    private static final int HISTORY_LIMIT = 500;
    private static final long POLL_MILLIS = 200;

    private static final DefaultRedisScript<Long> PUBLISH_EVENT_SCRIPT = new DefaultRedisScript<>(
            "if not redis.call('HGET',KEYS[1],'eventSeq') then redis.call('HSET',KEYS[1],'eventSeq',ARGV[5]) end; " +
                    "local id=redis.call('HINCRBY',KEYS[1],'eventSeq',1); " +
                    "redis.call('HSET',KEYS[1],'event:'..id,id..'\\t'..ARGV[1]..'\\t'..ARGV[2]); " +
                    "local old=id-tonumber(ARGV[3]); if old>0 then redis.call('HDEL',KEYS[1],'event:'..old) end; " +
                    "redis.call('EXPIRE',KEYS[1],ARGV[4]); return id",
            Long.class);

    private static final DefaultRedisScript<Long> ADVANCE_OUTBOX_FENCE_SCRIPT = new DefaultRedisScript<>(
            "local field='outboxFence:'..ARGV[1]; local old=redis.call('HGET',KEYS[1],field); " +
                    "if old and tonumber(old)>tonumber(ARGV[2]) then return 0 end; " +
                    "redis.call('HSET',KEYS[1],field,ARGV[2]); redis.call('EXPIRE',KEYS[1],ARGV[3]); return 1",
            Long.class);

    private static final DefaultRedisScript<Long> PUBLISH_TERMINAL_SCRIPT = new DefaultRedisScript<>(
            "local fenceField='outboxFence:'..ARGV[1]; " +
                    "if redis.call('HGET',KEYS[1],fenceField)~=ARGV[2] then return -1 end; " +
                    "local marker='outboxCommit:'..ARGV[1]; " +
                    "if redis.call('HGET',KEYS[1],marker) then return 2 end; " +
                    "if not redis.call('HGET',KEYS[1],'eventSeq') then redis.call('HSET',KEYS[1],'eventSeq',ARGV[15]) end; " +
                    "local first=redis.call('HINCRBY',KEYS[1],'eventSeq',1); " +
                    "local second=redis.call('HINCRBY',KEYS[1],'eventSeq',1); " +
                    "redis.call('HSET',KEYS[1],'event:'..first,first..'\\t'..ARGV[3]..'\\t'..ARGV[4]); " +
                    "redis.call('HSET',KEYS[1],'event:'..second,second..'\\t'..ARGV[5]..'\\t'..ARGV[6]); " +
                    "for _,id in ipairs({first,second}) do local old=id-tonumber(ARGV[13]); if old>0 then redis.call('HDEL',KEYS[1],'event:'..old) end end; " +
                    "redis.call('HSET',KEYS[1],'status',ARGV[7],'progress',ARGV[8],'planId',ARGV[9]," +
                    "'backingStore','MYSQL','updatedAt',ARGV[10]); " +
                    "if ARGV[11]=='' then redis.call('HDEL',KEYS[1],'error','errorCode') else " +
                    "redis.call('HSET',KEYS[1],'error',ARGV[11],'errorCode',ARGV[12]) end; " +
                    "redis.call('HSET',KEYS[1],marker,'1'); redis.call('EXPIRE',KEYS[1],ARGV[14]); return 1",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final TaskStateStore taskStateStore;

    public long publish(String taskId, String eventName, A2AStreamEvent event) {
        try {
            String payload = json(event);
            String encodedPayload = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            Long id = redis.execute(PUBLISH_EVENT_SCRIPT, List.of(taskKey(taskId)),
                    eventName, encodedPayload, Integer.toString(HISTORY_LIMIT),
                    Long.toString(TTL.getSeconds()), Long.toString(legacySequence(taskId)));
            if (id == null) throw new IllegalStateException("Redis did not allocate an event id");
            return id;
        } catch (RuntimeException e) {
            log.warn("Unable to publish AI SSE event: taskId={}, event={}, causeType={}",
                    taskId, eventName, e.getClass().getSimpleName());
            return -1;
        }
    }

    /**
     * Advances the Redis fencing token before an Outbox attempt is processed. All terminal writes
     * carry that token, so a worker whose SQL lease expired cannot publish after a newer attempt.
     */
    public boolean advanceOutboxFence(String taskId, String eventId, int attempt) {
        Long advanced = redis.execute(ADVANCE_OUTBOX_FENCE_SCRIPT, List.of(taskKey(taskId)),
                eventId, Integer.toString(attempt), Long.toString(TTL.getSeconds()));
        return Long.valueOf(1L).equals(advanced);
    }

    public boolean isOutboxEventCommitted(String taskId, String eventId) {
        Object marker = redis.opsForHash().get(taskKey(taskId), "outboxCommit:" + eventId);
        return "1".equals(marker == null ? null : marker.toString());
    }

    public TerminalWrite publishPlanCompletedOnce(String eventId, int attempt, String taskId,
                                                   String planId, TravelPlanResult result) {
        String finalPlan = result.getFinalPlan();
        if (finalPlan == null || finalPlan.isBlank()) {
            try { finalPlan = objectMapper.writeValueAsString(result); }
            catch (JsonProcessingException e) { throw new IllegalStateException("Unable to serialize plan result", e); }
        }
        return publishTerminal(eventId, attempt, taskId, planId,
                A2AStreamEvent.token(finalPlan), A2AStreamEvent.taskDone(result),
                "SUCCEEDED", 100, "", "");
    }

    public TerminalWrite publishPlanRollbackOnce(String eventId, int attempt, String taskId,
                                                  String planId, String error) {
        String safeError = error == null || error.isBlank() ? "AI plan synchronization failed" : error;
        return publishTerminal(eventId, attempt, taskId, planId,
                A2AStreamEvent.taskUpdate(Map.of("taskId", taskId, "status", "failed", "message", safeError)),
                A2AStreamEvent.error(Map.of("message", safeError)),
                "FAILED", 0, safeError, "AI_SYNC_FAILED");
    }

    private TerminalWrite publishTerminal(String eventId, int attempt, String taskId, String planId,
                                          A2AStreamEvent first, A2AStreamEvent second,
                                          String status, int progress, String error, String errorCode) {
        Long result = redis.execute(PUBLISH_TERMINAL_SCRIPT, List.of(taskKey(taskId)),
                eventId, Integer.toString(attempt), first.event(), encode(first), second.event(), encode(second),
                status, Integer.toString(progress), planId == null ? "" : planId,
                Instant.now().toString(), error == null ? "" : error, errorCode == null ? "" : errorCode,
                Integer.toString(HISTORY_LIMIT), Long.toString(TTL.getSeconds()),
                Long.toString(legacySequence(taskId)));
        if (result == null) throw new IllegalStateException("Redis did not acknowledge terminal event");
        if (result == -1) return TerminalWrite.FENCED;
        if (result == 2) return TerminalWrite.ALREADY_COMMITTED;
        if (result == 1) return TerminalWrite.COMMITTED;
        throw new IllegalStateException("Unexpected Redis terminal-event result: " + result);
    }

    private String encode(A2AStreamEvent event) {
        return Base64.getEncoder().encodeToString(json(event).getBytes(StandardCharsets.UTF_8));
    }

    private String json(A2AStreamEvent event) {
        try { return objectMapper.writeValueAsString(event); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Unable to serialize SSE event", e); }
    }

    public boolean hasEvents(String taskId) {
        return !readAfter(taskId, 0).isEmpty();
    }

    public boolean reserveReplay(String taskId) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                    replayKey(taskId), "1", Duration.ofSeconds(30)));
        } catch (RuntimeException e) {
            log.warn("Unable to reserve AI SSE replay: taskId={}, causeType={}",
                    taskId, e.getClass().getSimpleName());
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
                TaskStateStore.TaskState state = taskStateStore.get(taskId);
                if (state == null) {
                    emitter.complete();
                    return;
                }
                List<TaskEvent> events = readAfter(taskId, cursor);
                for (TaskEvent event : events) {
                    if (!active.get()) return;
                    if (isTerminalNotification(event) && !isTerminal(state.status())) break;
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(event.id()))
                            .name(event.name())
                            .data(event.payload()));
                    cursor = event.id();
                }

                if (isTerminal(state.status()) && events.isEmpty()) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (IOException e) {
            log.debug("AI SSE client disconnected: taskId={}", taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (RuntimeException e) {
            log.warn("AI SSE stream failed: taskId={}, causeType={}", taskId, e.getClass().getSimpleName());
            emitter.completeWithError(e);
        }
    }

    private List<TaskEvent> readAfter(String taskId, long cursor) {
        Map<Object, Object> values = redis.opsForHash().entries(taskKey(taskId));
        List<TaskEvent> events = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : values.entrySet()) {
            String field = String.valueOf(entry.getKey());
            if (!field.startsWith("event:")) continue;
            addEvent(events, String.valueOf(entry.getValue()), cursor, taskId);
        }
        List<String> legacy = redis.opsForList().range(legacyEventsKey(taskId), 0, -1);
        if (legacy != null) for (String row : legacy) addEvent(events, row, cursor, taskId);
        events.sort(Comparator.comparingLong(TaskEvent::id));
        return events;
    }

    private void addEvent(List<TaskEvent> events, String row, long cursor, String taskId) {
        try {
            String[] parts = row.split("\\t", 3);
            if (parts.length != 3) return;
            long id = Long.parseLong(parts[0]);
            if (id <= cursor) return;
            String payload = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
            events.add(new TaskEvent(id, parts[1], payload));
        } catch (RuntimeException e) {
            log.warn("Malformed AI SSE event in Redis: taskId={}", taskId);
        }
    }

    private long legacySequence(String taskId) {
        String value = redis.opsForValue().get(legacySequenceKey(taskId));
        try { return value == null ? 0 : Math.max(0, Long.parseLong(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private boolean isTerminalNotification(TaskEvent event) {
        if ("token".equals(event.name()) || "task_done".equals(event.name()) || "error".equals(event.name())) {
            return true;
        }
        if (!"task_update".equals(event.name())) return false;
        try {
            String status = objectMapper.readTree(event.payload()).path("data").path("status").asText();
            return "failed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status);
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private String taskKey(String taskId) { return "a2a:task:" + taskId; }
    private String legacyEventsKey(String taskId) { return "a2a:events:" + taskId; }
    private String legacySequenceKey(String taskId) { return "a2a:event-seq:" + taskId; }
    private String replayKey(String taskId) { return "a2a:replay:" + taskId; }

    public enum TerminalWrite { COMMITTED, ALREADY_COMMITTED, FENCED }
    private record TaskEvent(long id, String name, String payload) { }
}
