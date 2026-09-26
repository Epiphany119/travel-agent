package com.travel.a2a.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 行程规划请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelPlanRequest {

    /**
     * 目的地城市
     */
    @JsonProperty("destination")
    @NotBlank
    @Size(max = 100)
    private String destination;

    /**
     * 天数
     */
    @JsonProperty("days")
    @Min(1)
    @Max(14)
    private int days;

    /**
     * 预算
     */
    @JsonProperty("budget")
    @DecimalMin("300")
    @DecimalMax("200000")
    private double budget;

    /**
     * 人数
     */
    @JsonProperty("travelers")
    @Min(1)
    @Max(12)
    private int travelers;

    /**
     * 旅行风格
     */
    @JsonProperty("travelStyle")
    @Size(max = 50)
    private String travelStyle;

    /**
     * 兴趣点
     */
    @JsonProperty("interests")
    private List<String> interests;
}
