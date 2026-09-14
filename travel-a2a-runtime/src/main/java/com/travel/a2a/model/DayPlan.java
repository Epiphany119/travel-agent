package com.travel.a2a.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 每日行程
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DayPlan {

    /**
     * 第几天
     */
    @JsonProperty("day")
    private int day;

    /**
     * 日期
     */
    @JsonProperty("date")
    private String date;

    @JsonProperty("theme")
    private String theme;

    /**
     * 天气
     */
    @JsonProperty("weather")
    private String weather;

    /**
     * 温度
     */
    @JsonProperty("temperature")
    private String temperature;

    /**
     * 行程安排（按时间顺序的活动）
     */
    @JsonProperty("activities")
    private List<Activity> activities;

    /**
     * 当日预算
     */
    @JsonProperty("dailyBudget")
    private double dailyBudget;

    /**
     * 活动
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Activity {

        /**
         * 时间，如 "09:00"
         */
        @JsonProperty("time")
        private String time;

        /**
         * 活动类型：sightseeing/meal/shopping/transport/rest
         */
        @JsonProperty("type")
        private String type;

        /**
         * 活动名称
         */
        @JsonProperty("name")
        private String name;

        /**
         * 关联的候选地点标识；休息等非地点活动可以为空。
         */
        @JsonProperty("placeId")
        private String placeId;

        /**
         * 地点
         */
        @JsonProperty("location")
        private String location;

        @JsonProperty("transport")
        private String transport;

        /**
         * 预计时长（分钟）
         */
        @JsonProperty("duration")
        private int duration;

        /**
         * 备注
         */
        @JsonProperty("notes")
        private String notes;

        /**
         * 费用
         *
         * <p>没有真实费用来源时保持 {@code null}，不能用 0 伪装成免费。</p>
         */
        @JsonProperty("cost")
        private Double cost;
    }
}
