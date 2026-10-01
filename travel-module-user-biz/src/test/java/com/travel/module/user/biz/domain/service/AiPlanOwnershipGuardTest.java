package com.travel.module.user.biz.domain.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiPlanOwnershipGuardTest {
    @Test
    void allowsThePlanOwner() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        assertDoesNotThrow(() -> new AiPlanOwnershipGuard(jdbc).requireOwned("plan_1", "user_1"));
    }

    @Test
    void rejectsForeignPlansWithoutDisclosingTheirExistence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new AiPlanOwnershipGuard(jdbc).requireOwned("plan_1", "user_2"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void failsClosedWhenOwnershipCannotBeChecked() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new AiPlanOwnershipGuard(jdbc).requireOwned("plan_1", "user_1"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }
}