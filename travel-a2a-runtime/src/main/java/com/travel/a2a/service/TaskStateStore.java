package com.travel.a2a.service;

import com.travel.a2a.persistence.AiTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** AI task state facade. MySQL is authoritative when available; Redis supports short-lived progress and dev fallback. */
@Slf4j
@Component
public class TaskStateStore {
    private static final Duration TTL = Duration.ofHours(24);
    private static final String IDEMPOTENCY_PREFIX = "a2a:idem:";
    private static final String CLAIM_PREFIX = "a2a:claim:";
    private final StringRedisTemplate redis;
    private final AiTaskRepository taskRepository;

    public TaskStateStore(StringRedisTemplate redis) { this(redis, null); }

    @Autowired
    public TaskStateStore(StringRedisTemplate redis, AiTaskRepository taskRepository) {
        this.redis = redis;
        this.taskRepository = taskRepository;
    }

    private String key(String id) { return "a2a:task:" + id; }

    public boolean create(String taskId) { return create(taskId, "system"); }

    public boolean create(String taskId, String ownerId) {
        String safeOwnerId = isBlank(ownerId) ? "system" : ownerId;
        return createOrGet(taskId, safeOwnerId, "default", null, "{}").created();
    }

    /** Creates a task once per owner/key and rejects reuse of a key with a different request body. */
    public TaskCreation createOrGet(String taskId, String ownerId, String tenantId,
                                    String idempotencyKey, String requestJson) {
        if (isBlank(ownerId)) throw new IllegalArgumentException("ownerId is required");
        if (requestJson == null) requestJson = "{}";
        validateIdempotencyKey(idempotencyKey);

        if (!isBlank(idempotencyKey)) {
            try {
                String redisTaskId = redis.opsForValue().get(idempotencyIndexKey(ownerId, idempotencyKey));
                if (!isBlank(redisTaskId)) return existingRedisTask(redisTaskId, ownerId, requestJson);
            } catch (ResponseStatusException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("璇诲彇 Redis 骞傜瓑绱㈠紩澶辫触锛屽皢灏濊瘯鏁版嵁搴? causeType={}", e.getClass().getSimpleName());
            }
        }

        if (taskRepository != null) {
            try {
                if (!isBlank(idempotencyKey)) {
                    var existing = taskRepository.findByIdempotency(ownerId, idempotencyKey);
                    if (existing.isPresent()) {
                        requireSameRequest(existing.get().requestJson(), requestJson);
                        mirrorSafely(existing.get());
                        return new TaskCreation(existing.get().taskId(), false);
                    }
                }
                taskRepository.insert(taskId, ownerId, tenantId, idempotencyKey, requestJson);
                try {
                    mirrorCreate(taskId, ownerId, idempotencyKey, requestJson);
                } catch (RuntimeException e) {
                    log.warn("AI 浠诲姟宸插啓鍏ユ暟鎹簱锛屼絾 Redis 闀滃儚澶辫触: taskId={}, causeType={}",
                            taskId, e.getClass().getSimpleName());
                }
                return new TaskCreation(taskId, true);
            } catch (DataAccessException e) {
                if (!isBlank(idempotencyKey)) {
                    try {
                        var existing = taskRepository.findByIdempotency(ownerId, idempotencyKey);
                        if (existing.isPresent()) {
                            requireSameRequest(existing.get().requestJson(), requestJson);
                            mirrorSafely(existing.get());
                            return new TaskCreation(existing.get().taskId(), false);
                        }
                    } catch (DataAccessException lookupError) {
                        log.warn("AI 浠诲姟骞傜瓑璁板綍鏌ヨ澶辫触: causeType={}", lookupError.getClass().getSimpleName());
                    }
                }
                log.error("AI 浠诲姟鏁版嵁搴撲笉鍙敤锛屽皢鏆傛椂浣跨敤 Redis 鐘舵€? taskId={}, causeType={}",
                        taskId, e.getClass().getSimpleName());
            }
        }

        return createInRedis(taskId, ownerId, idempotencyKey, requestJson);
    }

    private TaskCreation createInRedis(String taskId, String ownerId, String idempotencyKey, String requestJson) {
        if (isBlank(idempotencyKey)) {
            if (!mirrorCreate(taskId, ownerId, null, requestJson)) {
                throw new IllegalStateException("Unable to create AI task state");
            }
            return new TaskCreation(taskId, true);
        }

        String indexKey = idempotencyIndexKey(ownerId, idempotencyKey);
        String existingTaskId = redis.opsForValue().get(indexKey);
        if (!isBlank(existingTaskId)) return existingRedisTask(existingTaskId, ownerId, requestJson);

        if (!mirrorCreate(taskId, ownerId, idempotencyKey, requestJson)) {
            throw new IllegalStateException("Unable to create AI task state");
        }
        Boolean reserved = redis.opsForValue().setIfAbsent(indexKey, taskId, TTL);
        if (Boolean.TRUE.equals(reserved)) return new TaskCreation(taskId, true);

        // A concurrent request won the key. Discard the unreferenced task and return the winner.
        redis.delete(key(taskId));
        existingTaskId = redis.opsForValue().get(indexKey);
        if (isBlank(existingTaskId)) throw new IllegalStateException("骞傜瓑浠诲姟姝ｅ湪鍒涘缓锛岃閲嶈瘯");
        return existingRedisTask(existingTaskId, ownerId, requestJson);
    }

    private TaskCreation existingRedisTask(String taskId, String ownerId, String requestJson) {
        Map<Object, Object> values = redis.opsForHash().entries(key(taskId));
        if (values.isEmpty()) throw new IllegalStateException("Idempotent task state is temporarily unavailable");
        if (!Objects.equals(value(values.get("ownerId")), ownerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "浠诲姟涓嶅瓨鍦ㄦ垨鏃犳潈璁块棶");
        }
        requireSameFingerprint(value(values.get("requestFingerprint")), fingerprint(requestJson));
        return new TaskCreation(taskId, false);
    }

    private void requireSameRequest(String existingJson, String requestJson) {
        if (!Objects.equals(fingerprint(existingJson), fingerprint(requestJson))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key 宸茬敤浜庝笉鍚岀殑璇锋眰");
        }
    }

    private void requireSameFingerprint(String existingFingerprint, String requestFingerprint) {
        if (isBlank(existingFingerprint) || !existingFingerprint.equals(requestFingerprint)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key 宸茬敤浜庝笉鍚岀殑璇锋眰");
        }
    }

    private void validateIdempotencyKey(String key) {
        if (isBlank(key)) return;
        if (key.length() < 8 || key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key 鏍煎紡鏃犳晥");
        }
    }

    private String idempotencyIndexKey(String ownerId, String idempotencyKey) {
        return IDEMPOTENCY_PREFIX + fingerprint(ownerId + ":" + idempotencyKey);
    }

    private String fingerprint(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Atomically starts only a PENDING task. The database compare-and-set also recovers a created-but-unscheduled request. */
    public boolean claimExecution(String taskId) {
        if (taskRepository != null) {
            try {
                return taskRepository.claimExecution(taskId);
            } catch (DataAccessException e) {
                log.warn("AI 浠诲姟鏁版嵁搴撹棰嗗け璐ワ紝灏嗗皾璇?Redis: taskId={}, causeType={}",
                        taskId, e.getClass().getSimpleName());
            }
        }
        TaskState current = get(taskId);
        if (current == null || isTerminal(current.status()) || "RUNNING".equals(current.status())) return false;
        Boolean claimed = redis.opsForValue().setIfAbsent(CLAIM_PREFIX + taskId, "1", TTL);
        if (!Boolean.TRUE.equals(claimed)) return false;
        update(taskId, "RUNNING", 5, null, null);
        return true;
    }

    public void running(String taskId, int progress) { update(taskId, "RUNNING", progress, null, null); }
    public void succeed(String taskId) { update(taskId, "SUCCEEDED", 100, null, null); }
    public void fail(String taskId, String error) { update(taskId, "FAILED", 0, "AI_TASK_FAILED", error); }

    /** Database state wins over the short-lived Redis mirror whenever the table is available. */
    public TaskState get(String taskId) {
        if (taskRepository != null) {
            try {
                var durable = taskRepository.findByTaskId(taskId);
                if (durable.isPresent()) {
                    mirrorSafely(durable.get());
                    return fromRecord(durable.get());
                }
            } catch (DataAccessException e) {
                log.warn("AI 浠诲姟鏁版嵁搴撴煡璇㈠け璐? taskId={}, causeType={}", taskId, e.getClass().getSimpleName());
            }
        }
        Map<Object, Object> values = redis.opsForHash().entries(key(taskId));
        return values.isEmpty() ? null : fromRedis(taskId, values);
    }

    public boolean isOwner(String taskId, String ownerId) {
        TaskState state = get(taskId);
        return state != null && ownerId != null && !ownerId.isBlank() && ownerId.equals(state.ownerId());
    }

    public void cancel(String id) { update(id, "CANCELLED", 0, "AI_TASK_CANCELLED", "cancelled by user"); }

    private void update(String id, String status, int progress, String errorCode, String errorMessage) {
        TaskState current = get(id);
        if (current != null && isTerminal(current.status())) return;
        if (taskRepository != null) {
            try {
                taskRepository.updateState(id, status, progress, errorCode, errorMessage);
            } catch (DataAccessException e) {
                log.error("AI 浠诲姟鏁版嵁搴撶姸鎬佹洿鏂板け璐? taskId={}, status={}", id, status, e);
            }
        }
        try {
            if (Boolean.TRUE.equals(redis.hasKey(key(id)))) {
                redis.opsForHash().put(key(id), "status", status);
                redis.opsForHash().put(key(id), "progress", String.valueOf(progress));
                redis.opsForHash().put(key(id), "updatedAt", Instant.now().toString());
                if (errorCode != null) redis.opsForHash().put(key(id), "errorCode", errorCode);
                if (errorMessage != null) redis.opsForHash().put(key(id), "error", truncate(errorMessage, 1024));
                redis.expire(key(id), TTL);
            }
        } catch (RuntimeException e) {
            log.warn("鏇存柊 AI 浠诲姟 Redis 闀滃儚澶辫触: taskId={}, causeType={}", id, e.getClass().getSimpleName());
        }
    }

    private boolean mirrorCreate(String taskId, String ownerId, String idempotencyKey, String requestJson) {
        Boolean ok = redis.opsForHash().putIfAbsent(key(taskId), "status", "PENDING");
        if (!Boolean.TRUE.equals(ok)) return false;
        String now = Instant.now().toString();
        redis.opsForHash().put(key(taskId), "ownerId", ownerId);
        redis.opsForHash().put(key(taskId), "idempotencyKey", idempotencyKey == null ? "" : idempotencyKey);
        redis.opsForHash().put(key(taskId), "requestFingerprint", fingerprint(requestJson));
        redis.opsForHash().put(key(taskId), "progress", "0");
        redis.opsForHash().put(key(taskId), "createdAt", now);
        redis.opsForHash().put(key(taskId), "updatedAt", now);
        redis.expire(key(taskId), TTL);
        return true;
    }

    private void mirrorSafely(AiTaskRepository.TaskRecord record) {
        try { mirror(record); }
        catch (RuntimeException e) {
            log.warn("AI 浠诲姟 Redis 闀滃儚澶辫触: taskId={}, causeType={}",
                    record.taskId(), e.getClass().getSimpleName());
        }
    }

    private void mirror(AiTaskRepository.TaskRecord record) {
        redis.opsForHash().put(key(record.taskId()), "status", record.status());
        redis.opsForHash().put(key(record.taskId()), "ownerId", value(record.ownerId()));
        redis.opsForHash().put(key(record.taskId()), "idempotencyKey", value(record.idempotencyKey()));
        redis.opsForHash().put(key(record.taskId()), "requestFingerprint", fingerprint(record.requestJson()));
        redis.opsForHash().put(key(record.taskId()), "progress", String.valueOf(record.progress()));
        redis.opsForHash().put(key(record.taskId()), "attempt", String.valueOf(record.attempt()));
        redis.opsForHash().put(key(record.taskId()), "planId", value(record.planId()));
        redis.opsForHash().put(key(record.taskId()), "error", value(record.errorMessage()));
        redis.opsForHash().put(key(record.taskId()), "createdAt", instantText(record.createdAt()));
        redis.opsForHash().put(key(record.taskId()), "updatedAt", instantText(record.updatedAt()));
        redis.expire(key(record.taskId()), TTL);
        if (!isBlank(record.idempotencyKey())) {
            redis.opsForValue().setIfAbsent(
                    idempotencyIndexKey(record.ownerId(), record.idempotencyKey()), record.taskId(), TTL);
        }
    }

    private TaskState fromRedis(String taskId, Map<Object, Object> values) {
        String updated = value(values.get("updatedAt"));
        Instant updatedAt;
        try { updatedAt = Instant.parse(updated); } catch (RuntimeException ignored) { updatedAt = Instant.now(); }
        return new TaskState(taskId, value(values.get("status")), integer(values.get("progress")),
                valueOrNull(values.get("error")), updatedAt, value(values.get("ownerId")),
                valueOrNull(values.get("planId")), valueOrNull(values.get("idempotencyKey")));
    }

    private TaskState fromRecord(AiTaskRepository.TaskRecord record) {
        return new TaskState(record.taskId(), record.status(), record.progress(), record.errorMessage(),
                record.updatedAt() == null ? Instant.now() : record.updatedAt(), record.ownerId(),
                record.planId(), record.idempotencyKey());
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }
    private int integer(Object value) {
        try { return Integer.parseInt(String.valueOf(value)); } catch (RuntimeException ignored) { return 0; }
    }
    private String value(Object value) { return value == null ? "" : String.valueOf(value); }
    private String valueOrNull(Object value) { String text = value(value); return text.isBlank() ? null : text; }
    private String instantText(Instant value) { return value == null ? Instant.now().toString() : value.toString(); }
    private boolean isBlank(String value) { return value == null || value.isBlank(); }
    private String truncate(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }

    public record TaskCreation(String taskId, boolean created) { }
    public record TaskState(String taskId, String status, int progress, String error,
                            Instant updatedAt, String ownerId, String planId, String idempotencyKey) {
        public TaskState(String taskId, String status, int progress, String error,
                         Instant updatedAt, String ownerId) {
            this(taskId, status, progress, error, updatedAt, ownerId, null, null);
        }
    }
}
