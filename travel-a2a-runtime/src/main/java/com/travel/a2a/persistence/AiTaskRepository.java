package com.travel.a2a.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
                "SELECT task_id,owner_id,idempotency_key,request_json,output_json,status,progress,attempt,plan_id,error_code,error_message,created_at,started_at,completed_at,updated_at " +
                        "FROM ai_task WHERE task_id=? LIMIT 1",
                this::map, taskId);
        return rows.stream().findFirst();
    }

    public Optional<TaskRecord> findByIdempotency(String ownerId, String idempotencyKey) {
        if (isBlank(ownerId) || isBlank(idempotencyKey)) return Optional.empty();
        List<TaskRecord> rows = jdbcTemplate.query(
                "SELECT task_id,owner_id,idempotency_key,request_json,output_json,status,progress,attempt,plan_id,error_code,error_message,created_at,started_at,completed_at,updated_at " +
                        "FROM ai_task WHERE owner_id=? AND idempotency_key=? LIMIT 1",
                this::map, ownerId, idempotencyKey);
        return rows.stream().findFirst();
    }

    public void insert(String taskId, String ownerId, String tenantId,
                       String idempotencyKey, String requestJson) {
        jdbcTemplate.update(
                "INSERT INTO ai_task(task_id,owner_id,tenant_id,idempotency_key,request_json,status,progress,attempt,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,'PENDING',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                taskId, ownerId, valueOrDefault(tenantId, "default"), blankToNull(idempotencyKey), requestJson);
    }

    /** Compare-and-set prevents duplicate workers when the same idempotency key is retried. */
    public boolean claimExecution(String taskId) {
        return jdbcTemplate.update(
                "UPDATE ai_task SET status='RUNNING',progress=5,attempt=attempt+1," +
                        "started_at=COALESCE(started_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP " +
                        "WHERE task_id=? AND status='PENDING'",
                taskId) == 1;
    }

    public void updateState(String taskId, String status, int progress, String errorCode, String errorMessage) {
        if ("RUNNING".equals(status)) {
            jdbcTemplate.update(
                    "UPDATE ai_task SET status=?,progress=?,attempt=attempt+1,started_at=COALESCE(started_at,CURRENT_TIMESTAMP)," +
                            "error_code=NULL,error_message=NULL,updated_at=CURRENT_TIMESTAMP " +
                            "WHERE task_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                    status, progress, taskId);
            return;
        }
        if ("SUCCEEDED".equals(status)) {
            jdbcTemplate.update(
                    "UPDATE ai_task SET status=?,progress=?,error_code=NULL,error_message=NULL,completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                            "WHERE task_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                    status, progress, taskId);
            return;
        }
        jdbcTemplate.update(
                "UPDATE ai_task SET status=?,progress=?,error_code=?,error_message=?,completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE task_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                status, progress, errorCode, truncate(errorMessage, 1024), taskId);
    }

    public void saveOutcome(String taskId, String planId, String outputJson) {
        jdbcTemplate.update(
                "UPDATE ai_task SET plan_id=?,output_json=?,status='SUCCEEDED',progress=100,completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE task_id=? AND status NOT IN ('FAILED','CANCELLED')",
                planId, outputJson, taskId);
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
            Instant updatedAt) {
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
                toInstant(rs.getObject("updated_at", LocalDateTime.class)));
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
