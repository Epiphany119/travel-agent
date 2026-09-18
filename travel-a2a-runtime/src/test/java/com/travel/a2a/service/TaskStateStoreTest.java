package com.travel.a2a.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskStateStoreTest {
    @Test void terminalStateCannotBeOverwritten() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String,Object,Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.putIfAbsent(anyString(), eq("status"), eq("PENDING"))).thenReturn(true);
        when(redis.hasKey(anyString())).thenReturn(true);
        when(hash.get(anyString(), eq("status"))).thenReturn("SUCCEEDED");
        TaskStateStore store = new TaskStateStore(redis);
        assertTrue(store.create("t-1"));
        store.running("t-1", 20);
        verify(hash, never()).put(anyString(), eq("status"), eq("RUNNING"));
    }

    @Test void readsPersistedProgressAndStatus() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String,Object,Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.entries(anyString())).thenReturn(Map.of("status", "RUNNING", "progress", "40", "updatedAt", "2026-09-18T10:00:00Z"));
        TaskStateStore.TaskState state = new TaskStateStore(redis).get("t-2");
        assertEquals("RUNNING", state.status());
        assertEquals(40, state.progress());
    }
}
