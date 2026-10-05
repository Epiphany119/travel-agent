package com.travel.a2a.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AiTaskRepositoryStateTest {
    @Test
    void successCannotBypassTheOutboxSynchronizationBarrier() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiTaskRepository repository = new AiTaskRepository(jdbc);

        assertThrows(IllegalArgumentException.class,
                () -> repository.updateState("task-1", "SUCCEEDED", 100, null, null, 1));
        verifyNoInteractions(jdbc);
    }
}
