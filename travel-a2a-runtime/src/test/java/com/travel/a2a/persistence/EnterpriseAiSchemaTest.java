package com.travel.a2a.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnterpriseAiSchemaTest {
    @Test
    void allowsFallbackOnlyWhenAiTaskTableIsMissing() {
        var missingTaskTable = new BadSqlGrammarException("task lookup",
                "SELECT * FROM ai_task WHERE task_id=?",
                new SQLException("Table 'travel.ai_task' doesn't exist", "42S02", 1146));
        var missingOtherTable = new BadSqlGrammarException("plan lookup",
                "SELECT * FROM ai_plan WHERE plan_id=?",
                new SQLException("Table 'travel.ai_plan' doesn't exist", "42S02", 1146));

        assertTrue(EnterpriseAiSchema.isAiTaskTableMissing(missingTaskTable));
        assertFalse(EnterpriseAiSchema.isAiTaskTableMissing(missingOtherTable));
    }

    @Test
    void doesNotTreatDatabaseConnectionFailureAsMissingMigration() {
        var unavailable = new CannotGetJdbcConnectionException("database unavailable",
                new SQLException("Connection refused", "08001"));

        assertFalse(EnterpriseAiSchema.isAiTaskTableMissing(unavailable));
    }
}
