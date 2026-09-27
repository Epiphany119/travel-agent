package com.travel.a2a.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 行程规划结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelPlanResult {

    /** 可恢复的计划标识，保存、生命周期和商业转化都通过它关联。 */
    @JsonProperty("planId")
    private String planId;

    @JsonProperty("destination")
    private String destination;
    @JsonProperty("days")
    private int days;
    @JsonProperty("travelers")
    private int travelers;
    @JsonProperty("travelStyle")
    private String travelStyle;
    @JsonProperty("interests")
    private List<String> interests;
    @JsonProperty("strategyNotes")
    private List<String> strategyNotes;

    /**
     * 是否成功
     */
    @JsonProperty("success")
    private boolean success;

    /**
     * 每日行程
     */
    @JsonProperty("dayPlans")
    private List<DayPlan> dayPlans;

    /**
     * 数据警告（降级场景）
     */
    @JsonProperty("dataWarnings")
    private List<DataWarning> dataWarnings;

    /**
     * 天气信息
     */
    @JsonProperty("weather")
    private WeatherResult weather;

    /**
     * POI信息
     */
    @JsonProperty("pois")
    private List<PoiResult> pois;

    /**
     * 餐饮信息
     */
    @JsonProperty("meals")
    private List<MealResult> meals;

    /**
     * 预算估算
     */
    @JsonProperty("budget")
    private BudgetEstimate budget;

    /**
     * 最终行程文本（LLM优化后）
     */
    @JsonProperty("finalPlan")
    private String finalPlan;

    /**
     * 错误信息
     */
    @JsonProperty("error")
    private String error;
}
