package com.travel.a2a.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.a2a.model.TravelPlanResult;
import com.travel.a2a.service.AiPlanningMetrics;
import com.travel.a2a.service.TaskEventStore;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiOutboxDispatcherTest {
    private final AiOutboxRepository outbox = mock(AiOutboxRepository.class);
    private final TaskEventStore eventStore = mock(TaskEventStore.class);
    private final AiPlanningMetrics metrics = mock(AiPlanningMetrics.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiOutboxDispatcher dispatcher = new AiOutboxDispatcher(outbox, eventStore, objectMapper, metrics);

    @Test
    void successIsPublishedOnlyAfterDatabaseCommitBarrierAndThenMarkedSent() throws Exception {
        String payload = objectMapper.writeValueAsString(new AiOutboxPayload.PlanCompleted(
                "task-1", "plan-1", 1, result(), "gpt-test", 800));
        AiOutboxRepository.OutboxEvent event = event("AI_PLAN_CREATED", payload, "PROCESSING", 1);
        when(outbox.claimDue(16, 30)).thenReturn(List.of(event));
        when(outbox.markDatabaseCommitted("event-1", "task-1", 1, 1)).thenReturn(true);
        when(eventStore.advanceOutboxFence("task-1", "event-1", 2)).thenReturn(true);
        when(eventStore.publishPlanCompletedOnce("event-1", 2, "task-1", "plan-1", objectMapper.readValue(
                payload, AiOutboxPayload.PlanCompleted.class).result()))
                .thenReturn(TaskEventStore.TerminalWrite.COMMITTED);
        when(outbox.markSent("event-1")).thenReturn(true);

        dispatcher.dispatchDueEvents();

        InOrder order = inOrder(outbox, eventStore);
        order.verify(outbox).markDatabaseCommitted("event-1", "task-1", 1, 1);
        order.verify(eventStore).advanceOutboxFence("task-1", "event-1", 2);
        order.verify(eventStore).publishPlanCompletedOnce(eq("event-1"), eq(2), eq("task-1"),
                eq("plan-1"), any(TravelPlanResult.class));
        order.verify(outbox).markSent("event-1");
        verify(metrics).recordPlanOutcome("gpt-test", "succeeded", 800);
    }

    @Test
    void retryLimitCompensatesOnlyAfterFencingAndConfirmingRedisMarkerAbsent() throws Exception {
        String payload = objectMapper.writeValueAsString(new AiOutboxPayload.PlanCompleted(
                "task-1", "plan-1", 1, result(), "gpt-test", 800));
        AiOutboxRepository.OutboxEvent event = event("AI_PLAN_CREATED", payload, "DB_COMMITTED", 8);
        when(outbox.claimDue(16, 30)).thenReturn(List.of(event));
        when(eventStore.advanceOutboxFence("task-1", "event-1", 16)).thenReturn(true);
        when(eventStore.publishPlanCompletedOnce(eq("event-1"), eq(16), eq("task-1"),
                eq("plan-1"), any(TravelPlanResult.class))).thenThrow(new IllegalStateException("Redis timeout"));
        when(eventStore.advanceOutboxFence("task-1", "event-1", 17)).thenReturn(true);
        when(eventStore.isOutboxEventCommitted("task-1", "event-1")).thenReturn(false);
        when(outbox.compensatePlanSync(event, 8, "Redis timeout")).thenReturn(true);

        dispatcher.dispatchDueEvents();

        InOrder order = inOrder(eventStore, outbox);
        order.verify(eventStore).advanceOutboxFence("task-1", "event-1", 16);
        order.verify(eventStore).publishPlanCompletedOnce(eq("event-1"), eq(16), eq("task-1"),
                eq("plan-1"), any(TravelPlanResult.class));
        order.verify(eventStore).advanceOutboxFence("task-1", "event-1", 17);
        order.verify(eventStore).isOutboxEventCommitted("task-1", "event-1");
        order.verify(outbox).compensatePlanSync(event, 8, "Redis timeout");
        verify(outbox, never()).retryLater(anyString(), anyInt(), anyInt(), anyString());
    }

    @Test
    void staleOutboxWorkerDoesNotPublishWhenItsFenceWasSuperseded() throws Exception {
        String payload = objectMapper.writeValueAsString(new AiOutboxPayload.PlanCompleted(
                "task-1", "plan-1", 1, result(), "gpt-test", 800));
        AiOutboxRepository.OutboxEvent event = event("AI_PLAN_CREATED", payload, "DB_COMMITTED", 2);
        when(outbox.claimDue(16, 30)).thenReturn(List.of(event));
        when(eventStore.advanceOutboxFence("task-1", "event-1", 4)).thenReturn(false);

        dispatcher.dispatchDueEvents();

        verify(eventStore, never()).publishPlanCompletedOnce(
                anyString(), anyInt(), anyString(), anyString(), any(TravelPlanResult.class));
        verify(outbox, never()).markSent(anyString());
    }

    private AiOutboxRepository.OutboxEvent event(String type, String payload, String status, int attempts) {
        return new AiOutboxRepository.OutboxEvent(1, "event-1", "AI_PLAN", "plan-1",
                type, payload, status, attempts);
    }

    private TravelPlanResult result() {
        TravelPlanResult result = new TravelPlanResult();
        result.setFinalPlan("A test itinerary");
        return result;
    }
}
