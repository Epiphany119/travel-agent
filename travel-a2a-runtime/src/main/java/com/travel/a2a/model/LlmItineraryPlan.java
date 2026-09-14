package com.travel.a2a.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * LLM 行程候选的结构化协议。
 *
 * <p>该对象只代表模型提出的候选方案，不代表已经通过事实、时间和预算校验。
 * 最终展示内容必须由 Java 根据候选工具结果重新渲染。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LlmItineraryPlan {

    @JsonProperty("schemaVersion")
    private Integer schemaVersion;

    @JsonProperty("days")
    private List<Day> days;

    @JsonProperty("totalCost")
    private BigDecimal totalCost;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Day {

        @JsonProperty("dayNo")
        private Integer dayNo;

        @JsonProperty("date")
        private LocalDate date;

        @JsonProperty("items")
        private List<Item> items;

        @JsonProperty("dailyCost")
        private BigDecimal dailyCost;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {

        @JsonProperty("type")
        private ItemType type;

        @JsonProperty("placeId")
        private String placeId;

        @JsonProperty("startTime")
        private LocalTime startTime;

        @JsonProperty("endTime")
        private LocalTime endTime;

        /**
         * 仅作为模型候选值保留，服务端校验时会按工具数据重算，不直接信任。
         */
        @JsonProperty("estimatedCost")
        private BigDecimal estimatedCost;

        @JsonProperty("note")
        private String note;
    }

    public enum ItemType {
        ATTRACTION,
        RESTAURANT,
        TRANSPORT
    }
}
