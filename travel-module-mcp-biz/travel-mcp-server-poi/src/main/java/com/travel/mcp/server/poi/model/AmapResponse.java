package com.travel.mcp.server.poi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmapResponse {
    private boolean success;
    private String action;
    private String message;
    private int count;
    private int total;
    private Integer remainingCalls;
    private GeocodeResult geocodeResult;
    private RegeoResult regeoResult;
    private List<PoiInfo> pois;
    private List<InputTip> inputtips;
    private List<PathResult> paths;
    private List<TransitResult> transits;
    private List<DistanceResult> distanceResults;

    public static AmapResponse fallback(String message) {
        return AmapResponse.builder()
                .success(false)
                .message(message)
                .count(0)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeocodeResult {
        private String location;
        private String province;
        private String city;
        private String district;
        private String adcode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegeoResult {
        private String formattedAddress;
        private String province;
        private String city;
        private String district;
        private String adcode;
        private String township;
        private String streetNumber;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoiInfo {
        private String id;
        private String name;
        private String location;
        private String address;
        private String type;
        private String typecode;
        private String tel;
        private String pname;
        private String cityname;
        private String adname;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PathResult {
        private int distance;
        private int duration;
        private Integer tolls;
        private Integer tollDistance;
        private List<PathStep> steps;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PathStep {
        private String instruction;
        private String road;
        private int distance;
        private int duration;
        private String polyline;
        private String action;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransitResult {
        private int cost;
        private int duration;
        private int walkingDistance;
        private int nightflag;
        private List<TransitSegment> segments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransitSegment {
        private int walkingDistance;
        private int walkingDuration;
        private String busName;
        private String busType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DistanceResult {
        private String origin;
        private String destination;
        private int distance;
        private int duration;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InputTip {
        private String id;
        private String name;
        private String district;
        private String adcode;
        private String location;
        private String address;
        private String typecode;
    }
}
