package com.travel.a2a.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.a2a.service.AiPlanningMetrics;
import com.travel.a2a.service.TaskEventStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Delivers plan completion and compensation events through the MySQL Outbox. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiOutboxDispatcher {
    private static final int BATCH_SIZE = 16;
    private static final int LEASE_SECONDS = 30;
    private static final int MAX_SYNC_ATTEMPTS = 8;
    private static final AtomicBoolean SCHEMA_WARNING_REPORTED = new AtomicBoolean();

    private final AiOutboxRepository outboxRepository;
    private final TaskEventStore taskEventStore;
    private final ObjectMapper objectMapper;
    private final AiPlanningMetrics aiPlanningMetrics;

    @Scheduled(fixedDelayString = "${travel.ai.outbox.poll-interval-ms:1000}")
    public void dispatchDueEvents() {
        final List<AiOutboxRepository.OutboxEvent> events;
        try {
            events = outboxRepository.claimDue(BATCH_SIZE, LEASE_SECONDS);
            SCHEMA_WARNING_REPORTED.set(false);
        } catch (DataAccessException e) {
            if (EnterpriseAiSchema.isTableMissing(e, "ai_outbox_event")) {
                if (SCHEMA_WARNING_REPORTED.compareAndSet(false, true)) {
                    log.error("AI Outbox is unavailable; apply the deferred enterprise AI database migration before enabling plan completion");
                }
            } else {
                log.warn("Unable to claim AI Outbox events: causeType={}", e.getClass().getSimpleName());
            }
            return;
        }

        for (AiOutboxRepository.OutboxEvent event : events) {
            try {
                dispatch(event);
            } catch (Exception e) {
                handleFailure(event, e);
            }
        }
    }

    private void dispatch(AiOutboxRepository.OutboxEvent event) throws JsonProcessingException {
        switch (event.eventType()) {
            case "AI_PLAN_CREATED" -> dispatchPlanCompleted(event);
            case "AI_PLAN_SYNC_ROLLED_BACK" -> dispatchPlanRollback(event);
            default -> log.warn("Ignoring unsupported AI Outbox event type: {}", event.eventType());
        }
    }

    private void dispatchPlanCompleted(AiOutboxRepository.OutboxEvent event) throws JsonProcessingException {
        AiOutboxPayload.PlanCompleted payload = objectMapper.readValue(
                event.payloadJson(), AiOutboxPayload.PlanCompleted.class);
        int fence = deliveryFence(event.attempts());

        if ("PROCESSING".equals(event.status())
                && !outboxRepository.markDatabaseCommitted(
                        event.eventId(), payload.taskId(), payload.attempt(), event.attempts())) {
            return;
        }
        if (!taskEventStore.advanceOutboxFence(payload.taskId(), event.eventId(), fence)) return;

        try {
            TaskEventStore.TerminalWrite result = taskEventStore.publishPlanCompletedOnce(
                    event.eventId(), fence, payload.taskId(), payload.planId(), payload.result());
            if (result == TaskEventStore.TerminalWrite.FENCED) return;
            if (outboxRepository.markSent(event.eventId())) {
                aiPlanningMetrics.recordPlanOutcome(payload.modelName(), "succeeded", payload.latencyMs());
            }
        } catch (RuntimeException e) {
            if (event.attempts() < MAX_SYNC_ATTEMPTS) throw e;
            reconcileAfterRetryLimit(event, payload.taskId(), payload.planId(), e);
        }
    }

    private void dispatchPlanRollback(AiOutboxRepository.OutboxEvent event) throws JsonProcessingException {
        AiOutboxPayload.PlanSyncRollback payload = objectMapper.readValue(
                event.payloadJson(), AiOutboxPayload.PlanSyncRollback.class);
        int fence = deliveryFence(event.attempts());
        if (!taskEventStore.advanceOutboxFence(payload.taskId(), event.eventId(), fence)) return;
        TaskEventStore.TerminalWrite result = taskEventStore.publishPlanRollbackOnce(
                event.eventId(), fence, payload.taskId(), payload.planId(), payload.message());
        if (result != TaskEventStore.TerminalWrite.FENCED && outboxRepository.markSent(event.eventId())) {
            aiPlanningMetrics.recordPlanOutcome(payload.modelName(), "failed", payload.latencyMs());
        }
    }

    private void reconcileAfterRetryLimit(AiOutboxRepository.OutboxEvent event, String taskId,
                                         String planId, RuntimeException originalFailure) {
        try {
            // Odd fence values are reserved for reconciliation; the next delivery gets a larger even value.
            if (!taskEventStore.advanceOutboxFence(taskId, event.eventId(), deliveryFence(event.attempts()) + 1)) return;
            if (taskEventStore.isOutboxEventCommitted(taskId, event.eventId())) {
                if (outboxRepository.markSent(event.eventId())) {
                    AiOutboxPayload.PlanCompleted payload = objectMapper.readValue(
                            event.payloadJson(), AiOutboxPayload.PlanCompleted.class);
                    aiPlanningMetrics.recordPlanOutcome(payload.modelName(), "succeeded", payload.latencyMs());
                }
                return;
            }
            if (outboxRepository.compensatePlanSync(event, event.attempts(), safeMessage(originalFailure))) {
                log.error("AI plan synchronization exhausted retries and was compensated: taskId={}, planId={}, eventId={}",
                        taskId, planId, event.eventId(), originalFailure);
            }
        } catch (Exception reconciliationFailure) {
            outboxRepository.retryLater(event.eventId(), event.attempts(), retryDelaySeconds(event.attempts()),
                    safeMessage(reconciliationFailure));
            log.error("AI plan synchronization remains pending because Redis commit state could not be reconciled: taskId={}, eventId={}",
                    taskId, event.eventId(), reconciliationFailure);
        }
    }

    private void handleFailure(AiOutboxRepository.OutboxEvent event, Exception failure) {
        if ("AI_PLAN_CREATED".equals(event.eventType()) && event.attempts() >= MAX_SYNC_ATTEMPTS) {
            try {
                AiOutboxPayload.PlanCompleted payload = objectMapper.readValue(
                        event.payloadJson(), AiOutboxPayload.PlanCompleted.class);
                reconcileAfterRetryLimit(event, payload.taskId(), payload.planId(),
                        failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure));
                return;
            } catch (Exception decodeFailure) {
                failure.addSuppressed(decodeFailure);
            }
        }
        try {
            outboxRepository.retryLater(event.eventId(), event.attempts(),
                    retryDelaySeconds(event.attempts()), safeMessage(failure));
        } catch (Exception retryFailure) {
            log.error("Unable to schedule AI Outbox retry: eventId={}", event.eventId(), retryFailure);
        }
        log.warn("AI Outbox delivery failed: eventId={}, type={}, attempt={}, causeType={}",
                event.eventId(), event.eventType(), event.attempts(), failure.getClass().getSimpleName());
    }

    private int retryDelaySeconds(int attempt) {
        int exponent = Math.min(8, Math.max(0, attempt - 1));
        return Math.min(300, 1 << exponent);
    }

    private int deliveryFence(int attempt) {
        return Math.multiplyExact(attempt, 2);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }
}
