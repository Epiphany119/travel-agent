package com.travel.a2a.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskStateStoreIdempotencyTest {
    @Test
    void redisFallbackReusesSameTaskAndRejectsChangedPayload() throws Exception {
        String requestJson = "{\"destination\":\"Tokyo\"}";
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null, null, "task-1", "task-1");
        when(hashes.putIfAbsent(anyString(), eq("status"), eq("PENDING"))).thenReturn(true);
        when(values.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(hashes.entries("a2a:task:task-1")).thenReturn(Map.<Object, Object>of(
                "ownerId", "user-1", "requestFingerprint", fingerprint(requestJson)));

        TaskStateStore store = new TaskStateStore(redis);
        TaskStateStore.TaskCreation created = store.createOrGet(
                "task-1", "user-1", "default", "request-key-123", requestJson);
        TaskStateStore.TaskCreation repeated = store.createOrGet(
                "task-2", "user-1", "default", "request-key-123", requestJson);

        assertTrue(created.created());
        assertFalse(repeated.created());
        assertEquals("task-1", repeated.taskId());

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> store.createOrGet("task-3", "user-1", "default", "request-key-123", "{}"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
    }

    private String fingerprint(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}