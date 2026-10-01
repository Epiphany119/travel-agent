package com.travel.a2a.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Low-cardinality AI model and planning metrics; user and plan identifiers are never metric labels. */
@Component
@RequiredArgsConstructor
public class AiPlanningMetrics {
    private final MeterRegistry registry;

    public void recordPlanOutcome(String model, String outcome, long elapsedMillis) {
        String safeModel = safeTag(model);
        String safeOutcome = "succeeded".equals(outcome) || "failed".equals(outcome) || "cancelled".equals(outcome)
                ? outcome : "failed";
        Counter.builder("travel.ai.plans")
                .tag("model", safeModel).tag("outcome", safeOutcome).register(registry).increment();
        Timer.builder("travel.ai.plan.duration")
                .tag("model", safeModel).tag("outcome", safeOutcome)
                .register(registry).record(Math.max(0, elapsedMillis), TimeUnit.MILLISECONDS);
    }

    public void recordModelResponse(Object chatResponse, String model, long elapsedNanos) {
        recordModelCall(model, "success", elapsedNanos);
        Object metadata = invoke(chatResponse, "getMetadata");
        Object usage = invoke(metadata, "getUsage");
        addTokens(model, "prompt", tokenCount(usage, "getPromptTokens"));
        long completionTokens = tokenCount(usage, "getGenerationTokens");
        if (completionTokens < 0) completionTokens = tokenCount(usage, "getCompletionTokens");
        addTokens(model, "completion", completionTokens);
    }

    public void recordModelFailure(String model, long elapsedNanos) {
        recordModelCall(model, "failure", elapsedNanos);
    }

    private void recordModelCall(String model, String outcome, long elapsedNanos) {
        String safeModel = safeTag(model);
        Counter.builder("travel.ai.model.calls")
                .tag("model", safeModel).tag("outcome", outcome).register(registry).increment();
        Timer.builder("travel.ai.model.duration")
                .tag("model", safeModel).tag("outcome", outcome)
                .register(registry).record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    private void addTokens(String model, String kind, long count) {
        if (count < 0) return;
        Counter.builder("travel.ai.model.tokens")
                .tag("model", safeTag(model)).tag("kind", kind)
                .register(registry).increment(count);
    }

    private long tokenCount(Object usage, String methodName) {
        Object value = invoke(usage, methodName);
        return value instanceof Number number ? number.longValue() : -1;
    }

    private Object invoke(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private String safeTag(String value) {
        if (value == null) return "unknown";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9._:-]{1,64}") ? normalized : "unknown";
    }
}