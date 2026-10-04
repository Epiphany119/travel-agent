package com.travel.a2a.service;

import com.travel.a2a.persistence.AiTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskStateStoreExecutionLeaseTest {
    @Test
    void fallsBackToRedisWhenDatabaseHasNoRowForTheTask() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AiTaskRepository repository = mock(AiTaskRepository.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        when(repository.claimExecution("task-1", 90)).thenReturn(null);
        when(repository.findByTaskId("task-1")).thenReturn(Optional.empty());
        when(hashes.entries("a2a:task:task-1")).thenReturn(Map.of(
                "status", "PENDING", "ownerId", "user-1", "attempt", "0", "backingStore", "REDIS"));
        when(values.setIfAbsent(eq("a2a:claim:task-1"), eq("1"), any())).thenReturn(true);

        int attempt = new TaskStateStore(redis, repository).claimExecutionAttempt("task-1");

        assertEquals(1, attempt);
        verify(hashes).put("a2a:task:task-1", "status", "RUNNING");
        verify(hashes).put("a2a:task:task-1", "attempt", "1");
    }

    @Test
    void doesNotUseStaleRedisWhenDatabaseRowHasALiveLease() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AiTaskRepository repository = mock(AiTaskRepository.class);
        when(repository.claimExecution("task-1", 90)).thenReturn(null);
        when(repository.findByTaskId("task-1")).thenReturn(Optional.of(record("RUNNING", 2)));

        int attempt = new TaskStateStore(redis, repository).claimExecutionAttempt("task-1");

        assertEquals(-1, attempt);
        verifyNoInteractions(redis);
    }

    @Test
    void returnsNewDatabaseAttemptForClaimedTask() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AiTaskRepository repository = mock(AiTaskRepository.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(repository.claimExecution("task-1", 90)).thenReturn(3);
        when(repository.findByTaskId("task-1")).thenReturn(Optional.of(record("RUNNING", 3)));

        assertEquals(3, new TaskStateStore(redis, repository).claimExecutionAttempt("task-1"));
        verify(hashes).put("a2a:task:task-1", "attempt", "3");
    }

    @Test
    void databaseConnectionFailureDoesNotFallBackToRedisTaskState() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AiTaskRepository repository = mock(AiTaskRepository.class);
        when(repository.findByTaskId("task-1")).thenThrow(new TransientDataAccessResourceException(
                "database unavailable", new SQLException("Connection refused", "08001")));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new TaskStateStore(redis, repository).get("task-1"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        verifyNoInteractions(redis);
    }

    @Test
    void ignoresStaleMysqlMirrorWhenDatabaseHasNoTaskRow() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AiTaskRepository repository = mock(AiTaskRepository.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(repository.findByTaskId("task-1")).thenReturn(Optional.empty());
        when(hashes.entries("a2a:task:task-1")).thenReturn(Map.of(
                "status", "PENDING", "ownerId", "user-1", "attempt", "0", "backingStore", "MYSQL"));

        assertNull(new TaskStateStore(redis, repository).get("task-1"));
    }

    @Test
    void doesNotReuseMysqlIdempotencyMirrorWhenDatabaseHasNoTaskRow() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AiTaskRepository repository = mock(AiTaskRepository.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("task-old");
        when(hashes.entries("a2a:task:task-old")).thenReturn(Map.of(
                "status", "SUCCEEDED", "ownerId", "user-1", "attempt", "1", "backingStore", "MYSQL"));
        when(repository.findByTaskId("task-old")).thenReturn(Optional.empty());
        when(repository.findByIdempotency("user-1", "request-key-1")).thenReturn(Optional.empty());

        TaskStateStore.TaskCreation creation = new TaskStateStore(redis, repository).createOrGet(
                "task-new", "user-1", "default", "request-key-1", "{}");

        assertTrue(creation.created());
        assertEquals("task-new", creation.taskId());
        verify(repository).insert("task-new", "user-1", "default", "request-key-1", "{}");
    }

    private AiTaskRepository.TaskRecord record(String status, int attempt) {
        Instant now = Instant.now();
        return new AiTaskRepository.TaskRecord(
                "task-1", "user-1", "request-key-1", "{}", null, status,
                5, attempt, null, null, null, now, now, null, now);
    }
}
