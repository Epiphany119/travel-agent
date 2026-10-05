package com.travel.a2a.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * AI 任务的数据库仓储。
 *
 * <p>Redis 适合推送进度，不适合作为唯一事实来源。该仓储把任务输入、状态、
 * 幂等键和最终输出保存到 MySQL，使服务重启后仍可查询和恢复。</p>
 */
@Repository
@RequiredArgsConstructor
public class AiTaskRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<TaskRecord> findByTaskId(String taskId) {
        List<TaskRecord> rows = jdbcTemplate.query(
                "SELECT task_id,owner_id,idempotency_key,request_json,output_json,status,progress,attempt,plan_id,error_code,error_message,created_at,started_at,completed_at,updated_at,'SENT' AS outbox_status " +
                        "FROM ai_task WHERE task_id=? LIMIT 1",
                this::map, taskId);
        return rows.stream().findFirst().map(this::attachOutboxStatus);
    }

    public Optional<TaskRecord> findByIdempotency(String ownerId, String idempotencyKey) {
        if (isBlank(ownerId) || isBlank(idempotencyKey)) return Optional.empty();
        List<TaskRecord> rows = jdbcTemplate.query(
                "SELECT task_id,owner_id,idempotency_key,request_json,output_json,status,progress,attempt,plan_id,error_code,error_message,created_at,started_at,completed_at,updated_at,'SENT' AS outbox_status " +
                        "FROM ai_task WHERE owner_id=? AND idempotency_key=? LIMIT 1",
                this::map, ownerId, idempotencyKey);
        return rows.stream().findFirst().map(this::attachOutboxStatus);
    }

    public void insert(String taskId, String ownerId, String tenantId,
                       String idempotencyKey, String requestJson) {
        jdbcTemplate.update(
                "INSERT INTO ai_task(task_id,owner_id,tenant_id,idempotency_key,request_json,status,progress,attempt,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,'PENDING',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                taskId, ownerId, valueOrDefault(tenantId, "default"), blankToNull(idempotencyKey), requestJson);
    }

    /**
     * Claims a pending task or reclaims a task whose lease expired.
     * The attempt counter is a fencing token for work from an older execution.
     */
    @Transactional
    public Integer claimExecution(String taskId, int leaseSeconds) {
        int updated = jdbcTemplate.update(
                "UPDATE ai_task SET status='RUNNING',progress=5,attempt=attempt+1," +
                        "started_at=COALESCE(started_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP " +
                        "WHERE task_id=? AND (status='PENDING' OR " +
                        "(status='RUNNING' AND updated_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? SECOND)))",
                taskId, leaseSeconds);
        if (updated != 1) return null;
        return jdbcTemplate.queryForObject(
                "SELECT attempt FROM ai_task WHERE task_id=?",
                Integer.class, taskId);
    }

    /** Refreshes a live execution lease without changing its fencing token. */
    public boolean heartbeat(String taskId, int attempt) {
        return jdbcTemplate.update(
                "UPDATE ai_task SET updated_at=CURRENT_TIMESTAMP " +
                        "WHERE task_id=? AND status='RUNNING' AND attempt=?",
                taskId, attempt) == 1;
    }

    public int updateState(String taskId, String status, int progress, String errorCode,
                           String errorMessage, Integer expectedAttempt) {
        if ("SUCCEEDED".equals(status)) {
            throw new IllegalArgumentException("AI task success must pass through the Outbox synchronization barrier");
        }
        String attemptPredicate = expectedAttempt == null ? "" : " AND attempt=?";
        if ("RUNNING".equals(status)) {
            String sql = "UPDATE ai_task SET status=?,progress=?,started_at=COALESCE(started_at,CURRENT_TIMESTAMP)," +
                    "error_code=NULL,error_message=NULL,updated_at=CURRENT_TIMESTAMP " +
                    "WHERE task_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','SYNC_PENDING')" + attemptPredicate;
            return expectedAttempt == null
                    ? jdbcTemplate.update(sql, status, progress, taskId)
                    : jdbcTemplate.update(sql, status, progress, taskId, expectedAttempt);
        }
        String sql = "UPDATE ai_task SET status=?,progress=?,error_code=?,error_message=?,completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                "WHERE task_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','SYNC_PENDING')" + attemptPredicate;
        return expectedAttempt == null
                ? jdbcTemplate.update(sql, status, progress, errorCode, truncate(errorMessage, 1024), taskId)
                : jdbcTemplate.update(sql, status, progress, errorCode, truncate(errorMessage, 1024), taskId, expectedAttempt);
    }

    public boolean cancelTask(String taskId) {
        return jdbcTemplate.update(
                "UPDATE ai_task SET status='CANCELLED',progress=0,error_code='AI_TASK_CANCELLED'," +
                        "error_message='cancelled by user',completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE task_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','SYNC_PENDING')",
                taskId) == 1;
    }

    public record TaskRecord(
            String taskId,
            String ownerId,
            String idempotencyKey,
            String requestJson,
            String outputJson,
            String status,
            int progress,
            int attempt,
            String planId,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt,
            String outboxStatus) {
        public TaskRecord(String taskId, String ownerId, String idempotencyKey, String requestJson,
                          String outputJson, String status, int progress, int attempt, String planId,
                          String errorCode, String errorMessage, Instant createdAt, Instant startedAt,
                          Instant completedAt, Instant updatedAt) {
            this(taskId, ownerId, idempotencyKey, requestJson, outputJson, status, progress, attempt,
                    planId, errorCode, errorMessage, createdAt, startedAt, completedAt, updatedAt, "SENT");
        }
    }

    private TaskRecord attachOutboxStatus(TaskRecord task) {
        if (isBlank(task.planId()) && !"SYNC_PENDING".equals(task.status())) return task;
        try {
            List<String> statuses = jdbcTemplate.query(
                    "SELECT status FROM ai_outbox_event WHERE " +
                            "(aggregate_type='AI_PLAN' AND aggregate_id=?) OR " +
                            "(aggregate_type='AI_TASK' AND aggregate_id=?) " +
                            "ORDER BY id DESC LIMIT 1",
                    (rs, rowNum) -> rs.getString("status"), task.planId(), task.taskId());
            String status = statuses.isEmpty() ? "MISSING" : statuses.get(0);
            return copyWithOutboxStatus(task, status);
        } catch (DataAccessException e) {
            if (EnterpriseAiSchema.isTableMissing(e, "ai_outbox_event")) {
                return copyWithOutboxStatus(task, "MISSING");
            }
            throw e;
        }
    }

    private TaskRecord copyWithOutboxStatus(TaskRecord task, String outboxStatus) {
        return new TaskRecord(task.taskId(), task.ownerId(), task.idempotencyKey(), task.requestJson(),
                task.outputJson(), task.status(), task.progress(), task.attempt(), task.planId(),
                task.errorCode(), task.errorMessage(), task.createdAt(), task.startedAt(),
                task.completedAt(), task.updatedAt(), outboxStatus);
    }

    private TaskRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new TaskRecord(
                rs.getString("task_id"),
                rs.getString("owner_id"),
                rs.getString("idempotency_key"),
                rs.getString("request_json"),
                rs.getString("output_json"),
                rs.getString("status"),
                rs.getInt("progress"),
                rs.getInt("attempt"),
                rs.getString("plan_id"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                toInstant(rs.getObject("created_at", LocalDateTime.class)),
                toInstant(rs.getObject("started_at", LocalDateTime.class)),
                toInstant(rs.getObject("completed_at", LocalDateTime.class)),
                toInstant(rs.getObject("updated_at", LocalDateTime.class)),
                rs.getString("outbox_status"));
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private String valueOrDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
