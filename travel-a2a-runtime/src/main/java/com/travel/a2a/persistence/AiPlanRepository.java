package com.travel.a2a.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.a2a.model.DayPlan;
import com.travel.a2a.model.TravelPlanRequest;
import com.travel.a2a.model.TravelPlanResult;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.MDC;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 计划版本仓储和领域事件写入器。
 *
 * <p>完成一个 AI 任务时，计划主表、不可变版本、审计、调用成本和 outbox
 * 在同一事务中写入。后续通知、订单和运营分析只消费 outbox，不从 SSE 猜测业务结果。</p>
 */
@Repository
@RequiredArgsConstructor
public class AiPlanRepository {

    private static final Set<String> TERMINAL_PLAN_STATUSES = Set.of("ARCHIVED");
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public Completion saveCompleted(String ownerId, String taskId, TravelPlanRequest request,
                                    TravelPlanResult result, long latencyMs,
                                    String provider, String modelName, String promptVersion) {
        Optional<PlanRecord> existing = findByTaskId(taskId);
        if (existing.isPresent()) {
            result.setPlanId(existing.get().planId());
            return new Completion(existing.get().planId(), existing.get().currentVersion(), false);
        }

        String planId = "plan_" + UUID.randomUUID().toString().replace("-", "");
        result.setPlanId(planId);
        String requestJson = toJson(request);
        String outputJson = toJson(result);
        int warningCount = result.getDataWarnings() == null ? 0 : result.getDataWarnings().size();
        BigDecimal qualityScore = BigDecimal.valueOf(Math.max(0, 100 - warningCount * 10L));
        String qualityStatus = warningCount == 0 ? "VALIDATED" : "DEGRADED";
        LocalDate startDate = firstDate(result.getDayPlans());
        LocalDate endDate = lastDate(result.getDayPlans());

        jdbcTemplate.update(
                "INSERT INTO ai_plan(plan_id,task_id,owner_id,tenant_id,destination,lifecycle_status,current_version,start_date,end_date,created_at,updated_at) " +
                        "VALUES(?,?,?,'default',?,'DRAFT',1,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                planId, taskId, ownerId, valueOrDefault(request.getDestination(), ""), startDate, endDate);
        jdbcTemplate.update(
                "INSERT INTO ai_plan_version(plan_id,version_no,request_json,output_json,quality_status,quality_score,warning_json,provider,model_name,prompt_version,created_by,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                planId, 1, requestJson, outputJson, qualityStatus, qualityScore,
                toJson(result.getDataWarnings()), valueOrDefault(provider, "unknown"),
                valueOrDefault(modelName, "unknown"), valueOrDefault(promptVersion, "v1"), ownerId);
        jdbcTemplate.update(
                "UPDATE ai_task SET plan_id=?,output_json=?,status='SUCCEEDED',progress=100,completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE task_id=? AND status NOT IN ('FAILED','CANCELLED')",
                planId, outputJson, taskId);
        jdbcTemplate.update(
                "INSERT INTO ai_usage_ledger(task_id,owner_id,tenant_id,provider,model_name,prompt_tokens,completion_tokens,latency_ms,estimated_cost,status,created_at) " +
                        "VALUES(?,?, 'default',?,?,NULL,NULL,?,NULL, 'SUCCEEDED',CURRENT_TIMESTAMP)",
                taskId, ownerId, valueOrDefault(provider, "unknown"), valueOrDefault(modelName, "unknown"), latencyMs);
        writeAudit(ownerId, "AI_PLAN_CREATED", "AI_PLAN", planId, "SUCCESS",
                toJson(Map.of("taskId", taskId, "version", 1, "qualityStatus", qualityStatus)));
        jdbcTemplate.update(
                "INSERT INTO ai_outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload_json,status,attempts,next_attempt_at,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString().replace("-", ""), "AI_PLAN", planId,
                "AI_PLAN_CREATED", outputJson);
        return new Completion(planId, 1, true);
    }

    public Optional<PlanRecord> findByTaskId(String taskId) {
        List<PlanRecord> rows = jdbcTemplate.query(
                "SELECT plan_id,task_id,owner_id,destination,lifecycle_status,current_version,start_date,end_date,created_at,updated_at " +
                        "FROM ai_plan WHERE task_id=? LIMIT 1", this::mapPlan, taskId);
        return rows.stream().findFirst();
    }

    public Optional<PlanRecord> findByPlanId(String planId) {
        List<PlanRecord> rows = jdbcTemplate.query(
                "SELECT plan_id,task_id,owner_id,destination,lifecycle_status,current_version,start_date,end_date,created_at,updated_at " +
                        "FROM ai_plan WHERE plan_id=? LIMIT 1", this::mapPlan, planId);
        return rows.stream().findFirst();
    }

    public Map<String, Object> getVersion(String planId, int version, String ownerId) {
        PlanRecord plan = findByPlanId(planId).orElse(null);
        if (plan == null || !plan.ownerId().equals(ownerId)) return null;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT plan_id,version_no,request_json,output_json,quality_status,quality_score,warning_json,provider,model_name,prompt_version,created_by,created_at " +
                        "FROM ai_plan_version WHERE plan_id=? AND version_no=? LIMIT 1", planId, version);
        if (rows.isEmpty()) return null;
        return rows.get(0);
    }

    @Transactional
    public PlanRecord transition(String planId, String ownerId, String targetStatus) {
        PlanRecord plan = findByPlanId(planId).orElse(null);
        if (plan == null) return null;
        if (!plan.ownerId().equals(ownerId)) throw new SecurityException("无权操作该计划");
        if (!isAllowed(plan.lifecycleStatus(), targetStatus)) {
            throw new IllegalStateException("计划状态不能从 " + plan.lifecycleStatus() + " 变为 " + targetStatus);
        }
        jdbcTemplate.update("UPDATE ai_plan SET lifecycle_status=?,updated_at=CURRENT_TIMESTAMP WHERE plan_id=?", targetStatus, planId);
        writeAudit(ownerId, "AI_PLAN_STATUS_CHANGED", "AI_PLAN", planId, "SUCCESS",
                "{\"from\":\"" + plan.lifecycleStatus() + "\",\"to\":\"" + targetStatus + "\"}");
        return findByPlanId(planId).orElseThrow();
    }

    private void writeAudit(String actorId, String action, String resourceType, String resourceId,
                            String outcome, String metadataJson) {
        jdbcTemplate.update(
                "INSERT INTO ai_audit_event(event_id,actor_id,tenant_id,action,resource_type,resource_id,outcome,trace_id,metadata_json,created_at) " +
                        "VALUES(?,?,'default',?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString().replace("-", ""), actorId, action, resourceType, resourceId, outcome,
                MDC.get("traceId"), metadataJson);
    }

    private boolean isAllowed(String current, String target) {
        if (target == null || target.isBlank() || TERMINAL_PLAN_STATUSES.contains(current)) return false;
        return switch (current) {
            case "DRAFT" -> Set.of("CONFIRMED", "ARCHIVED").contains(target);
            case "CONFIRMED" -> Set.of("IN_TRIP", "ARCHIVED").contains(target);
            case "IN_TRIP" -> Set.of("COMPLETED", "ARCHIVED").contains(target);
            case "COMPLETED" -> Set.of("PUBLISHED", "ARCHIVED").contains(target);
            case "PUBLISHED" -> Set.of("ARCHIVED").contains(target);
            default -> false;
        };
    }

    private PlanRecord mapPlan(ResultSet rs, int rowNum) throws SQLException {
        return new PlanRecord(
                rs.getString("plan_id"), rs.getString("task_id"), rs.getString("owner_id"),
                rs.getString("destination"), rs.getString("lifecycle_status"), rs.getInt("current_version"),
                rs.getObject("start_date", java.sql.Date.class) == null ? null : rs.getObject("start_date", java.sql.Date.class).toLocalDate(),
                rs.getObject("end_date", java.sql.Date.class) == null ? null : rs.getObject("end_date", java.sql.Date.class).toLocalDate(),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class));
    }

    private LocalDate firstDate(List<DayPlan> days) {
        if (days == null) return null;
        return days.stream().map(DayPlan::getDate).filter(this::isDate).map(LocalDate::parse).min(LocalDate::compareTo).orElse(null);
    }

    private LocalDate lastDate(List<DayPlan> days) {
        if (days == null) return null;
        return days.stream().map(DayPlan::getDate).filter(this::isDate).map(LocalDate::parse).max(LocalDate::compareTo).orElse(null);
    }

    private boolean isDate(String value) {
        if (value == null || value.isBlank()) return false;
        try { LocalDate.parse(value); return true; } catch (RuntimeException ignored) { return false; }
    }

    private String toJson(Object value) {
        try { return value == null ? "null" : objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("无法保存 AI 计划 JSON", e); }
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record Completion(String planId, int version, boolean created) { }

    public record PlanRecord(String planId, String taskId, String ownerId, String destination,
                             String lifecycleStatus, int currentVersion, LocalDate startDate,
                             LocalDate endDate, LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
