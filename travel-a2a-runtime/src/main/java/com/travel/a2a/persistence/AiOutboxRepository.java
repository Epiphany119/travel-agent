package com.travel.a2a.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AiOutboxRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** Uses short row-lock transactions supported by MySQL 5.7 and 8.0. */
    @Transactional
    public List<OutboxEvent> claimDue(int limit, int leaseSeconds) {
        List<OutboxEvent> due = jdbcTemplate.query(
                "SELECT id,event_id,aggregate_type,aggregate_id,event_type,payload_json,status,attempts " +
                        "FROM ai_outbox_event WHERE event_type IN ('AI_PLAN_CREATED','AI_PLAN_SYNC_ROLLED_BACK') " +
                        "AND status IN ('PENDING','PROCESSING','DB_COMMITTED') " +
                        "AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT ? FOR UPDATE",
                this::map, limit);
        for (OutboxEvent event : due) {
            String claimedStatus = "DB_COMMITTED".equals(event.status()) ? "DB_COMMITTED" : "PROCESSING";
            jdbcTemplate.update(
                    "UPDATE ai_outbox_event SET status=?,attempts=attempts+1," +
                            "next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL ? SECOND),updated_at=CURRENT_TIMESTAMP " +
                            "WHERE id=?",
                    claimedStatus, leaseSeconds, event.id());
        }
        return due.stream().map(event -> event.withClaim(
                "DB_COMMITTED".equals(event.status()) ? "DB_COMMITTED" : "PROCESSING",
                event.attempts() + 1)).toList();
    }

    @Transactional
    public boolean markDatabaseCommitted(String eventId, String taskId, int taskAttempt, int deliveryAttempt) {
        OutboxState eventState = stateForUpdate(eventId);
        if (eventState == null || eventState.attempts() != deliveryAttempt) return false;
        if ("SENT".equals(eventState.status()) || "DB_COMMITTED".equals(eventState.status())) return true;
        if (!"PROCESSING".equals(eventState.status())) return false;

        List<TaskExecution> tasks = jdbcTemplate.query(
                "SELECT status,attempt FROM ai_task WHERE task_id=? FOR UPDATE",
                (rs, rowNum) -> new TaskExecution(rs.getString("status"), rs.getInt("attempt")), taskId);
        if (tasks.isEmpty()) return false;
        TaskExecution task = tasks.get(0);
        if ("SYNC_PENDING".equals(task.status()) && task.attempt() == taskAttempt) {
            int updated = jdbcTemplate.update(
                    "UPDATE ai_task SET status='SUCCEEDED',progress=100,completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                            "WHERE task_id=? AND status='SYNC_PENDING' AND attempt=?",
                    taskId, taskAttempt);
            if (updated != 1) return false;
        } else if (!"SUCCEEDED".equals(task.status()) || task.attempt() != taskAttempt) {
            return false;
        }

        return jdbcTemplate.update(
                "UPDATE ai_outbox_event SET status='DB_COMMITTED',next_attempt_at=CURRENT_TIMESTAMP," +
                        "last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE event_id=? AND status='PROCESSING' AND attempts=?",
                eventId, deliveryAttempt) == 1;
    }

    /** Returns true only for the worker that transitions the row to SENT. */
    public boolean markSent(String eventId) {
        return jdbcTemplate.update(
                "UPDATE ai_outbox_event SET status='SENT',last_error=NULL,next_attempt_at=CURRENT_TIMESTAMP," +
                        "updated_at=CURRENT_TIMESTAMP WHERE event_id=? AND status IN ('PROCESSING','DB_COMMITTED')",
                eventId) == 1;
    }

    public void retryLater(String eventId, int deliveryAttempt, int delaySeconds, String error) {
        jdbcTemplate.update(
                "UPDATE ai_outbox_event SET status=CASE WHEN status='DB_COMMITTED' THEN 'DB_COMMITTED' ELSE 'PENDING' END," +
                        "next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL ? SECOND),last_error=?," +
                        "updated_at=CURRENT_TIMESTAMP WHERE event_id=? AND attempts=? AND status IN ('PROCESSING','DB_COMMITTED')",
                Math.max(1, delaySeconds), truncate(error, 1024), eventId, deliveryAttempt);
    }

    /**
     * Compensates only if this delivery still owns the SQL row. Locking the row serializes the
     * compensation against a new claim, so a newer worker cannot publish after rollback begins.
     */
    @Transactional
    public boolean compensatePlanSync(OutboxEvent event, int expectedDeliveryAttempt, String technicalReason) {
        OutboxState current = stateForUpdate(event.eventId());
        if (current == null || current.attempts() != expectedDeliveryAttempt
                || "SENT".equals(current.status()) || "FAILED".equals(current.status())) return false;

        AiOutboxPayload.PlanCompleted payload = readCompleted(event.payloadJson());
        String userMessage = "计划结果未能同步，系统已撤销本次计划";
        jdbcTemplate.update(
                "UPDATE ai_plan SET lifecycle_status='ARCHIVED',updated_at=CURRENT_TIMESTAMP " +
                        "WHERE plan_id=? AND lifecycle_status<>'ARCHIVED'",
                payload.planId());
        jdbcTemplate.update(
                "UPDATE ai_task SET status='FAILED',progress=0,error_code='AI_SYNC_FAILED',error_message=?," +
                        "completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE task_id=? AND status IN ('SYNC_PENDING','SUCCEEDED')",
                userMessage, payload.taskId());
        jdbcTemplate.update(
                "UPDATE ai_outbox_event SET status='FAILED',last_error=?,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE event_id=? AND attempts=? AND status<>'SENT'",
                truncate(technicalReason, 1024), event.eventId(), expectedDeliveryAttempt);

        String rollbackEventId = UUID.randomUUID().toString().replace("-", "");
        String rollbackPayload = toJson(new AiOutboxPayload.PlanSyncRollback(
                payload.taskId(), payload.planId(), event.eventId(), userMessage,
                payload.modelName(), payload.latencyMs()));
        jdbcTemplate.update(
                "INSERT INTO ai_outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload_json,status,attempts,next_attempt_at,created_at,updated_at) " +
                        "VALUES(?,'AI_TASK',?,'AI_PLAN_SYNC_ROLLED_BACK',?,'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                rollbackEventId, payload.taskId(), rollbackPayload);
        jdbcTemplate.update(
                "INSERT INTO ai_audit_event(event_id,actor_id,tenant_id,action,resource_type,resource_id,outcome,trace_id,metadata_json,created_at) " +
                        "VALUES(?,'system','default','AI_PLAN_SYNC_ROLLBACK','AI_PLAN',?,'COMPENSATED',NULL,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString().replace("-", ""), payload.planId(),
                toJson(java.util.Map.of("taskId", payload.taskId(), "sourceEventId", event.eventId())));
        return true;
    }

    private OutboxState stateForUpdate(String eventId) {
        List<OutboxState> rows = jdbcTemplate.query(
                "SELECT status,attempts FROM ai_outbox_event WHERE event_id=? FOR UPDATE",
                (rs, rowNum) -> new OutboxState(rs.getString("status"), rs.getInt("attempts")), eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AiOutboxPayload.PlanCompleted readCompleted(String payload) {
        try { return objectMapper.readValue(payload, AiOutboxPayload.PlanCompleted.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid AI plan outbox payload", e); }
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Unable to serialize outbox payload", e); }
    }

    private String truncate(String value, int max) {
        if (value == null) return "unknown";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private OutboxEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEvent(rs.getLong("id"), rs.getString("event_id"),
                rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                rs.getString("event_type"), rs.getString("payload_json"),
                rs.getString("status"), rs.getInt("attempts"));
    }

    private record TaskExecution(String status, int attempt) { }
    private record OutboxState(String status, int attempts) { }

    public record OutboxEvent(long id, String eventId, String aggregateType, String aggregateId,
                              String eventType, String payloadJson, String status, int attempts) {
        OutboxEvent withClaim(String claimedStatus, int claimedAttempts) {
            return new OutboxEvent(id, eventId, aggregateType, aggregateId, eventType,
                    payloadJson, claimedStatus, claimedAttempts);
        }
    }
}
