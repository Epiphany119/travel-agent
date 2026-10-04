package com.travel.a2a.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.a2a.model.TravelPlanRequest;
import com.travel.a2a.model.TravelPlanResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiPlanRepositoryLeaseTest {
    @Test
    void cancelledTaskCannotPersistAPlan() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class), eq("task-1")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString("status")).thenReturn("CANCELLED");
                    when(row.getInt("attempt")).thenReturn(1);
                    return List.of(mapper.mapRow(row, 0));
                });

        AiPlanRepository repository = new AiPlanRepository(jdbc, new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> repository.saveCompleted(
                "user-1", "task-1", new TravelPlanRequest(), new TravelPlanResult(),
                100L, "provider", "model", "prompt-v1", 1));

        verify(jdbc).query(contains("FOR UPDATE"), any(RowMapper.class), eq("task-1"));
        verifyNoMoreInteractions(jdbc);
    }
}
