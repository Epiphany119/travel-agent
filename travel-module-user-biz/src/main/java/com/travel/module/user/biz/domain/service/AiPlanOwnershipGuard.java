package com.travel.module.user.biz.domain.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Enforces ownership before a user resource is linked to an AI generated plan. */
@Component
@RequiredArgsConstructor
public class AiPlanOwnershipGuard {
    private final JdbcTemplate jdbcTemplate;

    public void requireOwned(String planId, String ownerId) {
        if (planId == null || planId.isBlank() || ownerId == null || ownerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "璁″垝涓嶅瓨鍦ㄦ垨鏃犳潈鎿嶄綔");
        }
        final Integer count;
        try {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_plan WHERE plan_id=? AND owner_id=?",
                    Integer.class, planId, ownerId);
        } catch (DataAccessException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Unable to verify AI plan ownership because the database is unavailable", e);
        }
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "璁″垝涓嶅瓨鍦ㄦ垨鏃犳潈鎿嶄綔");
        }
    }
}
