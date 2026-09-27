package com.travel.a2a.service;

import com.travel.a2a.persistence.AiTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * A2A 任务状态门面。
 *
 * <p>数据库是任务事实来源，Redis 只保存 SSE 所需的短期状态镜像。数据库迁移尚未执行
 * 时保留 Redis 降级能力，但会记录明确告警；生产启动检查应把该告警视为发布阻断项。</p>
 */
@Slf4j
@Component
public class TaskStateStore {
    private static final Duration TTL = Duration.ofHours(24);
    private final StringRedisTemplate redis;
    private final AiTaskRepository taskRepository;
    @Value("${a2a.persistence.fail-closed:false}")
    private boolean failClosed;

    /** 保留给原有单元测试和无数据库的轻量运行场景。 */
    public TaskStateStore(StringRedisTemplate redis) {
        this(redis, null);
    }

    @Autowired
    public TaskStateStore(StringRedisTemplate redis, AiTaskRepository taskRepository) {
        this.redis = redis;
        this.taskRepository = taskRepository;
    }

    private String key(String id) { return "a2a:task:" + id; }

    public boolean create(String taskId) { return create(taskId, null); }

    public boolean create(String taskId, String ownerId) {
        return createOrGet(taskId, ownerId, "default", null, "{}").created();
    }

    /** 创建任务或按用户和幂等键返回原任务。重复请求不会启动第二个模型调用。 */
    public TaskCreation createOrGet(String taskId, String ownerId, String tenantId,
                                    String idempotencyKey, String requestJson) {
        if (taskRepository != null) {
            try {
                if (!isBlank(idempotencyKey)) {
                    var existing = taskRepository.findByIdempotency(ownerId, idempotencyKey);
                    if (existing.isPresent()) {
                        mirror(existing.get());
                        return new TaskCreation(existing.get().taskId(), false);
                    }
                }
                taskRepository.insert(taskId, ownerId, tenantId, idempotencyKey, requestJson);
            } catch (DataAccessException e) {
                // 并发请求可能同时通过首次查询，唯一索引冲突时必须返回已有任务，
                // 不能退化成再次启动一条模型调用。
                if (!isBlank(idempotencyKey)) {
                    try {
                        var existing = taskRepository.findByIdempotency(ownerId, idempotencyKey);
                        if (existing.isPresent()) {
                            mirror(existing.get());
                            return new TaskCreation(existing.get().taskId(), false);
                        }
                    } catch (DataAccessException ignored) {
                        // 继续走开发环境降级路径，并保留原始错误日志。
                    }
                }
                if (failClosed) {
                    throw new IllegalStateException("AI 任务持久化不可用，拒绝启动未持久化任务", e);
                }
                log.error("AI 任务数据库写入失败，将暂时使用 Redis 镜像: taskId={}, cause={}",
                        taskId, e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage());
            }
        }
        boolean created = mirrorCreate(taskId, ownerId, idempotencyKey);
        return new TaskCreation(taskId, created);
    }

    public void running(String taskId, int progress) { update(taskId, "RUNNING", progress, null, null); }
    public void succeed(String taskId) { update(taskId, "SUCCEEDED", 100, null, null); }
    public void fail(String taskId, String error) { update(taskId, "FAILED", 0, "AI_TASK_FAILED", error); }

    public TaskState get(String taskId) {
        Map<Object, Object> values = redis.opsForHash().entries(key(taskId));
        if (!values.isEmpty()) return fromRedis(taskId, values);
        if (taskRepository != null) {
            try {
                var durable = taskRepository.findByTaskId(taskId);
                if (durable.isPresent()) {
                    mirror(durable.get());
                    return fromRecord(durable.get());
                }
            } catch (DataAccessException e) {
                if (failClosed) throw new IllegalStateException("AI 任务持久化不可用，无法读取任务", e);
                log.warn("AI 任务数据库查询失败: taskId={}, cause={}", taskId, e.getMessage());
            }
        }
        return null;
    }

    public boolean isOwner(String taskId, String ownerId) {
        TaskState state = get(taskId);
        return state != null && ownerId != null && !ownerId.isBlank() && ownerId.equals(state.ownerId());
    }

    public void cancel(String id) { update(id, "CANCELLED", 0, "AI_TASK_CANCELLED", "cancelled by user"); }

    private void update(String id, String status, int progress, String errorCode, String errorMessage) {
        TaskState current = get(id);
        if (current != null && isTerminal(current.status())) return;
        // Redis mock/客户端可能暂时拿不到完整 hash，但状态字段仍可用于终态保护。
        if (current == null && Boolean.TRUE.equals(redis.hasKey(key(id)))) {
            Object redisStatus = redis.opsForHash().get(key(id), "status");
            if (redisStatus != null && isTerminal(String.valueOf(redisStatus))) return;
        }
        if (taskRepository != null) {
            try {
                taskRepository.updateState(id, status, progress, errorCode, errorMessage);
            } catch (DataAccessException e) {
                log.error("AI 任务数据库状态更新失败: taskId={}, status={}", id, status, e);
                if (failClosed) throw new IllegalStateException("AI 任务持久化不可用，无法更新任务", e);
            }
        }
        if (Boolean.TRUE.equals(redis.hasKey(key(id)))) {
            redis.opsForHash().put(key(id), "status", status);
            redis.opsForHash().put(key(id), "progress", String.valueOf(progress));
            redis.opsForHash().put(key(id), "updatedAt", Instant.now().toString());
            if (errorCode != null) redis.opsForHash().put(key(id), "errorCode", errorCode);
            if (errorMessage != null) redis.opsForHash().put(key(id), "error", truncate(errorMessage, 1024));
            redis.expire(key(id), TTL);
        }
    }

    private boolean mirrorCreate(String taskId, String ownerId, String idempotencyKey) {
        Boolean ok = redis.opsForHash().putIfAbsent(key(taskId), "status", "PENDING");
        if (Boolean.TRUE.equals(ok)) {
            String now = Instant.now().toString();
            redis.opsForHash().put(key(taskId), "ownerId", ownerId == null ? "" : ownerId);
            redis.opsForHash().put(key(taskId), "idempotencyKey", idempotencyKey == null ? "" : idempotencyKey);
            redis.opsForHash().put(key(taskId), "progress", "0");
            redis.opsForHash().put(key(taskId), "createdAt", now);
            redis.opsForHash().put(key(taskId), "updatedAt", now);
            redis.expire(key(taskId), TTL);
        }
        return Boolean.TRUE.equals(ok);
    }

    private void mirror(AiTaskRepository.TaskRecord record) {
        redis.opsForHash().put(key(record.taskId()), "status", record.status());
        redis.opsForHash().put(key(record.taskId()), "ownerId", value(record.ownerId()));
        redis.opsForHash().put(key(record.taskId()), "idempotencyKey", value(record.idempotencyKey()));
        redis.opsForHash().put(key(record.taskId()), "progress", String.valueOf(record.progress()));
        redis.opsForHash().put(key(record.taskId()), "attempt", String.valueOf(record.attempt()));
        redis.opsForHash().put(key(record.taskId()), "planId", value(record.planId()));
        redis.opsForHash().put(key(record.taskId()), "error", value(record.errorMessage()));
        redis.opsForHash().put(key(record.taskId()), "createdAt", instantText(record.createdAt()));
        redis.opsForHash().put(key(record.taskId()), "updatedAt", instantText(record.updatedAt()));
        redis.expire(key(record.taskId()), TTL);
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
        /** 兼容旧测试和调用方。 */
        public TaskState(String taskId, String status, int progress, String error,
                         Instant updatedAt, String ownerId) {
            this(taskId, status, progress, error, updatedAt, ownerId, null, null);
        }
    }
}
