package com.travel.a2a.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POI结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoiResult {

    /**
     * 外部 POI 的稳定标识，用于约束 LLM 只能选择候选集中的地点。
     */
    @JsonProperty("placeId")
    private String placeId;

    /**
     * 名称
     */
    @JsonProperty("name")
    private String name;

    /**
     * 地址
     */
    @JsonProperty("address")
    private String address;

    /**
     * 类型
     */
    @JsonProperty("type")
    private String type;

    /**
     * 距离（米）
     */
    @JsonProperty("distance")
    private Integer distance;

    /**
     * 电话
     */
    @JsonProperty("tel")
    private String tel;

    /**
     * 经度
     */
    @JsonProperty("longitude")
    private Double longitude;

    /**
     * 纬度
     */
    @JsonProperty("latitude")
    private Double latitude;
}
