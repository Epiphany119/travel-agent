package com.travel.a2a.service;

import com.travel.a2a.persistence.AiTaskRepository;
import com.travel.a2a.persistence.EnterpriseAiSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** AI task state facade. MySQL is authoritative when available; Redis supports short-lived progress and dev fallback. */
@Slf4j
@Component
public class TaskStateStore {
    private static final Duration TTL = Duration.ofHours(24);
    private static final int EXECUTION_LEASE_SECONDS = 90;
    private static final String IDEMPOTENCY_PREFIX = "a2a:idem:";
    private static final String CLAIM_PREFIX = "a2a:claim:";
    private static final DefaultRedisScript<Long> CANCEL_TASK_SCRIPT = new DefaultRedisScript<>(
            "local status=redis.call('HGET',KEYS[1],'status'); " +
                    "if not status or status=='SUCCEEDED' or status=='FAILED' or status=='CANCELLED' then return 0 end; " +
                    "redis.call('HSET',KEYS[1],'status',ARGV[1],'progress',ARGV[2],'updatedAt',ARGV[3]," +
                    "'errorCode',ARGV[4],'error',ARGV[5]); redis.call('EXPIRE',KEYS[1],ARGV[6]); return 1",
            Long.class);
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
                if (!isBlank(redisTaskId)) {
                    TaskCreation existing = existingRedisTask(redisTaskId, ownerId, requestJson);
                    if (existing != null) return existing;
                }
            } catch (ResponseStatusException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("璇诲彇 Redis 骞傜瓑绱㈠紩澶辫触锛屽皢灏濊瘯鏁版嵁搴? causeType={}", e.getClass().getSimpleName());
            }
        }

        DataAccessException taskWriteFailure = null;
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
                    mirrorCreate(taskId, ownerId, idempotencyKey, requestJson, "MYSQL");
                } catch (RuntimeException e) {
                    log.warn("AI 浠诲姟宸插啓鍏ユ暟鎹簱锛屼絾 Redis 闀滃儚澶辫触: taskId={}, causeType={}",
                            taskId, e.getClass().getSimpleName());
                }
                return new TaskCreation(taskId, true);
            } catch (DataAccessException e) {
                taskWriteFailure = e;
                if (!isBlank(idempotencyKey)) {
                    try {
                        var existing = taskRepository.findByIdempotency(ownerId, idempotencyKey);
                        if (existing.isPresent()) {
                            requireSameRequest(existing.get().requestJson(), requestJson);
                            mirrorSafely(existing.get());
                            return new TaskCreation(existing.get().taskId(), false);
                        }
                    } catch (DataAccessException lookupError) {
                        if (!EnterpriseAiSchema.isAiTaskTableMissing(lookupError)) {
                            throw storageUnavailable(lookupError);
                        }
                        log.warn("AI 浠诲姟骞傜瓑璁板綍鏌ヨ澶辫触: causeType={}", lookupError.getClass().getSimpleName());
                    }
                }
                log.error("AI 浠诲姟鏁版嵁搴撲笉鍙敤锛屽皢鏆傛椂浣跨敤 Redis 鐘舵€? taskId={}, causeType={}",
                        taskId, e.getClass().getSimpleName());
            }
        }

        if (taskWriteFailure != null && !EnterpriseAiSchema.isAiTaskTableMissing(taskWriteFailure)) {
            throw storageUnavailable(taskWriteFailure);
        }
        return createInRedis(taskId, ownerId, idempotencyKey, requestJson);
    }

    private TaskCreation createInRedis(String taskId, String ownerId, String idempotencyKey, String requestJson) {
        if (isBlank(idempotencyKey)) {
            if (!mirrorCreate(taskId, ownerId, null, requestJson, "REDIS")) {
                throw new IllegalStateException("Unable to create AI task state");
            }
            return new TaskCreation(taskId, true);
        }

        String indexKey = idempotencyIndexKey(ownerId, idempotencyKey);
        String existingTaskId = redis.opsForValue().get(indexKey);
        if (!isBlank(existingTaskId)) {
            TaskCreation existing = existingRedisTask(existingTaskId, ownerId, requestJson);
            if (existing != null) return existing;
        }

        if (!mirrorCreate(taskId, ownerId, idempotencyKey, requestJson, "REDIS")) {
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
        if (taskRepository != null && "MYSQL".equals(value(values.get("backingStore")))) {
            try {
                var durable = taskRepository.findByTaskId(taskId);
                if (durable.isEmpty()) return null;
                if (!Objects.equals(durable.get().ownerId(), ownerId)) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI task is not available");
                }
                requireSameRequest(durable.get().requestJson(), requestJson);
                mirrorSafely(durable.get());
                return new TaskCreation(taskId, false);
            } catch (DataAccessException e) {
                if (!EnterpriseAiSchema.isAiTaskTableMissing(e)) throw storageUnavailable(e);
            }
        }
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
        if (key == null) return; // Internal legacy/test creation may omit a key; HTTP creation rejects missing/blank keys.
        if (key.isBlank() || key.length() < 8 || key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+")) {
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

    /** Claims a pending task or reclaims an expired MySQL execution lease. */
    public boolean claimExecution(String taskId) {
        return claimExecutionAttempt(taskId) > 0;
    }

    /** Returns the fencing attempt, or -1 when another live execution owns the task. */
    public int claimExecutionAttempt(String taskId) {
        if (taskRepository != null) {
            try {
                Integer attempt = taskRepository.claimExecution(taskId, EXECUTION_LEASE_SECONDS);
                if (attempt != null) {
                    taskRepository.findByTaskId(taskId).ifPresent(this::mirrorSafely);
                    return attempt;
                }
                // A real DB row that is terminal or has a live lease must not fall back to stale Redis.
                if (taskRepository.findByTaskId(taskId).isPresent()) return -1;
            } catch (DataAccessException e) {
                if (!EnterpriseAiSchema.isAiTaskTableMissing(e)) throw storageUnavailable(e);
                log.warn("AI 任务数据库抢占失败，将尝试 Redis: taskId={}, causeType={}",
                        taskId, e.getClass().getSimpleName());
            }
        }

        // Redis-only fallback is intentionally at-most-once for its 24h lifetime;
        // durable stale-lease recovery requires the migrated task table.
        TaskState current = get(taskId);
        if (current == null || isTerminal(current.status()) || "RUNNING".equals(current.status())) return -1;
        Boolean claimed = redis.opsForValue().setIfAbsent(CLAIM_PREFIX + taskId, "1", TTL);
        if (!Boolean.TRUE.equals(claimed)) return -1;
        int attempt = current.attempt() + 1;
        try {
            redis.opsForHash().put(key(taskId), "status", "RUNNING");
            redis.opsForHash().put(key(taskId), "progress", "5");
            redis.opsForHash().put(key(taskId), "attempt", String.valueOf(attempt));
            redis.opsForHash().put(key(taskId), "updatedAt", Instant.now().toString());
            redis.expire(key(taskId), TTL);
            return attempt;
        } catch (RuntimeException e) {
            log.error("Redis 任务抢占后更新状态失败: taskId={}", taskId, e);
            return -1;
        }
    }

    public boolean heartbeat(String taskId, int attempt) {
        if (taskRepository != null) {
            try {
                boolean alive = taskRepository.heartbeat(taskId, attempt);
                if (alive) {
                    taskRepository.findByTaskId(taskId).ifPresent(this::mirrorSafely);
                    return true;
                }
                if (taskRepository.findByTaskId(taskId).isPresent()) return false;
            } catch (DataAccessException e) {
                if (!EnterpriseAiSchema.isAiTaskTableMissing(e)) {
                    log.warn("AI task database heartbeat failed; lease renewal stopped: taskId={}", taskId, e);
                    return false;
                }
                log.warn("刷新 AI 任务租约失败，将检查 Redis: taskId={}, causeType={}",
                        taskId, e.getClass().getSimpleName());
            }
        }
        try {
            String redisAttempt = value(redis.opsForHash().get(key(taskId), "attempt"));
            String status = value(redis.opsForHash().get(key(taskId), "status"));
            if (!String.valueOf(attempt).equals(redisAttempt) || !"RUNNING".equals(status)) return false;
            redis.opsForHash().put(key(taskId), "updatedAt", Instant.now().toString());
            redis.expire(key(taskId), TTL);
            return true;
        } catch (RuntimeException e) {
            log.warn("刷新 Redis 任务租约失败: taskId={}, causeType={}", taskId, e.getClass().getSimpleName());
            return false;
        }
    }

    public boolean isCurrentExecution(String taskId, int attempt) {
        TaskState state = get(taskId);
        return state != null && "RUNNING".equals(state.status()) && state.attempt() == attempt;
    }

    public boolean isCurrentOrSucceededExecution(String taskId, int attempt) {
        TaskState state = get(taskId);
        return state != null && state.attempt() == attempt
                && ("RUNNING".equals(state.status()) || "SUCCEEDED".equals(state.status()));
    }

    public void running(String taskId, int progress) { update(taskId, "RUNNING", progress, null, null, null); }
    public void running(String taskId, int progress, int attempt) { update(taskId, "RUNNING", progress, null, null, attempt); }
    public void fail(String taskId, String error) { update(taskId, "FAILED", 0, "AI_TASK_FAILED", error, null); }
    public void fail(String taskId, String error, int attempt) { update(taskId, "FAILED", 0, "AI_TASK_FAILED", error, attempt); }

    /** Database state wins over the short-lived Redis mirror whenever the table is available. */
    public TaskState get(String taskId) {
        boolean allowUnmarkedRedis = taskRepository == null;
        if (taskRepository != null) {
            try {
                var durable = taskRepository.findByTaskId(taskId);
                if (durable.isPresent()) {
                    mirrorSafely(durable.get());
                    return fromRecord(durable.get());
                }
            } catch (DataAccessException e) {
                if (!EnterpriseAiSchema.isAiTaskTableMissing(e)) throw storageUnavailable(e);
                allowUnmarkedRedis = true;
                log.warn("AI 任务数据库查询失败: taskId={}, causeType={}", taskId, e.getClass().getSimpleName());
            }
        }
        Map<Object, Object> values = redis.opsForHash().entries(key(taskId));
        String backingStore = value(values.get("backingStore"));
        if (!"REDIS".equals(backingStore) && !allowUnmarkedRedis) return null;
        return values.isEmpty() ? null : fromRedis(taskId, values);
    }

    public boolean isOwner(String taskId, String ownerId) {
        TaskState state = get(taskId);
        return state != null && ownerId != null && !ownerId.isBlank() && ownerId.equals(state.ownerId());
    }

    /** Returns false if the task already reached any terminal state. */
    public boolean cancel(String id) {
        if (taskRepository != null) {
            try {
                if (taskRepository.findByTaskId(id).isPresent()) {
                    boolean cancelled = taskRepository.cancelTask(id);
                    if (cancelled) taskRepository.findByTaskId(id).ifPresent(this::mirrorSafely);
                    return cancelled;
                }
            } catch (DataAccessException e) {
                if (!EnterpriseAiSchema.isAiTaskTableMissing(e)) throw storageUnavailable(e);
                log.warn("AI 任务数据库取消失败，将尝试 Redis: taskId={}, causeType={}",
                        id, e.getClass().getSimpleName());
            }
        }
        try {
            Long cancelled = redis.execute(CANCEL_TASK_SCRIPT, List.of(key(id)),
                    "CANCELLED", "0", Instant.now().toString(), "AI_TASK_CANCELLED",
                    "cancelled by user", String.valueOf(TTL.getSeconds()));
            return Long.valueOf(1L).equals(cancelled);
        } catch (RuntimeException e) {
            log.warn("Redis 任务取消失败: taskId={}, causeType={}", id, e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean update(String id, String status, int progress, String errorCode,
                           String errorMessage, Integer expectedAttempt) {
        TaskState current = get(id);
        if (current == null || isTerminal(current.status())
                || expectedAttempt != null && current.attempt() != expectedAttempt) return false;
        if (taskRepository != null) {
            try {
                int updated = taskRepository.updateState(id, status, progress, errorCode, errorMessage, expectedAttempt);
                if (updated == 0 && taskRepository.findByTaskId(id).isPresent()) return false;
            } catch (DataAccessException e) {
                if (!EnterpriseAiSchema.isAiTaskTableMissing(e)) throw storageUnavailable(e);
                log.warn("AI 任务数据库状态更新失败，将更新 Redis: taskId={}, status={}, causeType={}",
                        id, status, e.getClass().getSimpleName());
            }
        }
        try {
            if (Boolean.TRUE.equals(redis.hasKey(key(id)))) {
                if (expectedAttempt != null
                        && !String.valueOf(expectedAttempt).equals(value(redis.opsForHash().get(key(id), "attempt")))) {
                    return false;
                }
                redis.opsForHash().put(key(id), "status", status);
                redis.opsForHash().put(key(id), "progress", String.valueOf(progress));
                redis.opsForHash().put(key(id), "updatedAt", Instant.now().toString());
                if (errorCode != null) redis.opsForHash().put(key(id), "errorCode", errorCode);
                if (errorMessage != null) redis.opsForHash().put(key(id), "error", truncate(errorMessage, 1024));
                redis.expire(key(id), TTL);
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("更新 AI 任务 Redis 镜像失败: taskId={}, causeType={}", id, e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean mirrorCreate(String taskId, String ownerId, String idempotencyKey,
                                 String requestJson, String backingStore) {
        Boolean ok = redis.opsForHash().putIfAbsent(key(taskId), "status", "PENDING");
        if (!Boolean.TRUE.equals(ok)) return false;
        String now = Instant.now().toString();
        redis.opsForHash().put(key(taskId), "ownerId", ownerId);
        redis.opsForHash().put(key(taskId), "idempotencyKey", idempotencyKey == null ? "" : idempotencyKey);
        redis.opsForHash().put(key(taskId), "requestFingerprint", fingerprint(requestJson));
        redis.opsForHash().put(key(taskId), "progress", "0");
        redis.opsForHash().put(key(taskId), "attempt", "0");
        redis.opsForHash().put(key(taskId), "backingStore", backingStore);
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
        String visibleStatus = visibleStatus(record);
        redis.opsForHash().put(key(record.taskId()), "status", visibleStatus);
        redis.opsForHash().put(key(record.taskId()), "backingStore", "MYSQL");
        redis.opsForHash().put(key(record.taskId()), "ownerId", value(record.ownerId()));
        redis.opsForHash().put(key(record.taskId()), "idempotencyKey", value(record.idempotencyKey()));
        redis.opsForHash().put(key(record.taskId()), "requestFingerprint", fingerprint(record.requestJson()));
        int visibleProgress = "SYNC_PENDING".equals(visibleStatus) ? 99 : record.progress();
        redis.opsForHash().put(key(record.taskId()), "progress", String.valueOf(visibleProgress));
        redis.opsForHash().put(key(record.taskId()), "attempt", String.valueOf(record.attempt()));
        redis.opsForHash().put(key(record.taskId()), "planId", value(record.planId()));
        redis.opsForHash().put(key(record.taskId()), "error",
                "SYNC_PENDING".equals(visibleStatus) ? "" : value(record.errorMessage()));
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
                valueOrNull(values.get("planId")), valueOrNull(values.get("idempotencyKey")),
                integer(values.get("attempt")));
    }

    private TaskState fromRecord(AiTaskRepository.TaskRecord record) {
        String visibleStatus = visibleStatus(record);
        int visibleProgress = "SYNC_PENDING".equals(visibleStatus) ? 99 : record.progress();
        return new TaskState(record.taskId(), visibleStatus, visibleProgress,
                "SYNC_PENDING".equals(visibleStatus) ? null : record.errorMessage(),
                record.updatedAt() == null ? Instant.now() : record.updatedAt(), record.ownerId(),
                record.planId(), record.idempotencyKey(), record.attempt());
    }

    private String visibleStatus(AiTaskRepository.TaskRecord record) {
        if ("SYNC_PENDING".equals(record.status())) return "SYNC_PENDING";
        if (("SUCCEEDED".equals(record.status()) || "FAILED".equals(record.status()))
                && !"SENT".equals(record.outboxStatus())) return "SYNC_PENDING";
        return record.status();
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status) || "SYNC_PENDING".equals(status);
    }
    private int integer(Object value) {
        try { return Integer.parseInt(String.valueOf(value)); } catch (RuntimeException ignored) { return 0; }
    }
    private String value(Object value) { return value == null ? "" : String.valueOf(value); }
    private String valueOrNull(Object value) { String text = value(value); return text.isBlank() ? null : text; }
    private String instantText(Instant value) { return value == null ? Instant.now().toString() : value.toString(); }
    private boolean isBlank(String value) { return value == null || value.isBlank(); }
    private String truncate(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }

    private ResponseStatusException storageUnavailable(DataAccessException cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI 任务数据库暂时不可用", cause);
    }

    public record TaskCreation(String taskId, boolean created) { }
    public record TaskState(String taskId, String status, int progress, String error,
                            Instant updatedAt, String ownerId, String planId, String idempotencyKey, int attempt) {
        public TaskState(String taskId, String status, int progress, String error,
                         Instant updatedAt, String ownerId, String planId, String idempotencyKey) {
            this(taskId, status, progress, error, updatedAt, ownerId, planId, idempotencyKey, 0);
        }
        public TaskState(String taskId, String status, int progress, String error,
                         Instant updatedAt, String ownerId) {
            this(taskId, status, progress, error, updatedAt, ownerId, null, null, 0);
        }
    }
}
