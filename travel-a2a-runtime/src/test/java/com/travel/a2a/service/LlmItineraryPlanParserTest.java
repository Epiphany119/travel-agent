package com.travel.a2a.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.a2a.model.LlmItineraryPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmItineraryPlanParserTest {

    private final LlmItineraryPlanParser parser = new LlmItineraryPlanParser(new ObjectMapper().findAndRegisterModules());

    @Test
    void parsesJsonInsideMarkdownFence() throws Exception {
        LlmItineraryPlan plan = parser.parse("```json\n"
                + "{\"schemaVersion\":1,\"days\":[{\"dayNo\":1,\"date\":\"2026-09-15\","
                + "\"items\":[{\"type\":\"ATTRACTION\",\"placeId\":\"poi:1\","
                + "\"startTime\":\"09:00\",\"endTime\":\"11:00\","
                + "\"estimatedCost\":null,\"note\":null}],\"dailyCost\":null}],"
                + "\"totalCost\":null}\n``` ");

        assertEquals(1, plan.getSchemaVersion());
        assertEquals(1, plan.getDays().size());
        assertNull(plan.getDays().get(0).getItems().get(0).getEstimatedCost());
    }

    @Test
    void rejectsUnknownActionFieldInsteadOfIgnoringIt() {
        String json = "{\"schemaVersion\":1,\"days\":[],\"totalCost\":null,"
                + "\"action\":\"BUY_PREMIUM_PACKAGE\"}";

        assertThrows(IllegalArgumentException.class, () -> parser.parse(json));
    }

    @Test
    void rejectsNaturalLanguageNumber() {
        String json = "{\"schemaVersion\":1,\"days\":[{\"dayNo\":1,"
                + "\"date\":\"2026-09-15\",\"items\":[],\"dailyCost\":\"一百元\"}],"
                + "\"totalCost\":null}";

        assertThrows(IllegalArgumentException.class, () -> parser.parse(json));
    }

    @Test
    void rejectsNonJsonExplanationAroundPayload() {
        String json = "说明文字\n{\"schemaVersion\":1,\"days\":[],\"totalCost\":null}";

        assertThrows(IllegalArgumentException.class, () -> parser.parse(json));
    }

    @Test
    void rejectsDuplicateJsonKeys() {
        String json = "{\"schemaVersion\":1,\"schemaVersion\":1,\"days\":[],\"totalCost\":null}";

        assertThrows(Exception.class, () -> parser.parse(json));
    }
}
