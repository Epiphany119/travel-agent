package com.travel.a2a.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 餐饮结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MealResult {

    /**
     * 外部餐厅的稳定标识，用于约束 LLM 只能选择候选集中的地点。
     */
    @JsonProperty("placeId")
    private String placeId;

    /**
     * 餐厅名称
     */
    @JsonProperty("name")
    private String name;

    /**
     * 地址
     */
    @JsonProperty("address")
    private String address;

    /**
     * 菜系类型
     */
    @JsonProperty("cuisine")
    private String cuisine;

    /**
     * 人均价格
     */
    @JsonProperty("avgPrice")
    private Integer avgPrice;

    /**
     * 评分
     */
    @JsonProperty("rating")
    private Double rating;
}
