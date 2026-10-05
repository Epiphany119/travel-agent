package com.travel.a2a.persistence;

import com.travel.a2a.model.TravelPlanResult;

public final class AiOutboxPayload {
    private AiOutboxPayload() { }

    public record PlanCompleted(String taskId, String planId, int attempt, TravelPlanResult result,
                                String modelName, long latencyMs) { }

    public record PlanSyncRollback(String taskId, String planId, String sourceEventId, String message,
                                   String modelName, long latencyMs) { }
}
