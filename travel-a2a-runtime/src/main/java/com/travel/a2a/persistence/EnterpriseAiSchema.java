package com.travel.a2a.persistence;

import org.springframework.dao.DataAccessException;
import java.sql.SQLException;
import java.util.Locale;

/** Classifies the one schema condition that permits the local Redis-only fallback. */
public final class EnterpriseAiSchema {
    private EnterpriseAiSchema() { }

    /**
     * Redis fallback is allowed only when the enterprise task table is absent, which identifies
     * the explicitly supported pre-migration development mode. Connection and other SQL errors
     * must fail closed instead of silently creating a second source of task state.
     */
    public static boolean isAiTaskTableMissing(DataAccessException failure) {
        return isTableMissing(failure, "ai_task");
    }

    public static boolean isTableMissing(DataAccessException failure, String tableName) {
        String expectedTable = tableName.toLowerCase(Locale.ROOT);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                boolean missingTable = "42S02".equals(state) || "42P01".equals(state)
                        || sqlException.getErrorCode() == 1146;
                String message = sqlException.getMessage();
                if (missingTable && message != null
                        && message.toLowerCase(Locale.ROOT).contains(expectedTable)) {
                    return true;
                }
            }
        }
        return false;
    }
}
