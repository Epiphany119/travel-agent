package com.travel.common.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.travel.common.exception.RateLimitException;
import com.travel.common.http.HttpClientSupport;
import com.travel.common.ratelimit.RateLimitService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 高德地图工具类
 * 提供地理编码、逆地理编码、POI搜索、路径规划、距离测量功能，带熔断器保护
 */
@Slf4j
@Component
public class PoiTool implements Function<PoiTool.AmapRequest, PoiTool.AmapResponse> {

    // API URLs
    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String REGEO_URL = "https://restapi.amap.com/v3/geocode/regeo";
    private static final String POI_SEARCH_URL = "https://restapi.amap.com/v3/place/text";
    private static final String INPUTTIPS_URL = "https://restapi.amap.com/v3/assistant/inputtips";
    private static final String WALKING_URL = "https://restapi.amap.com/v3/direction/walking";
    private static final String TRANSIT_URL = "https://restapi.amap.com/v3/direction/transit/integrated";
    private static final String DRIVING_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String BICYCLING_URL = "https://restapi.amap.com/v3/direction/bicycling";
    private static final String DISTANCE_URL = "https://restapi.amap.com/v3/distance";
    private static final String SERVICE_NAME = "amap";

    @Value("${gaode.api-key:}")
    @JsonProperty("api-key")
    private String apiKey;

    @Value("${gaode.enabled:true}")
    private boolean enabled;

    @Autowired(required = false)
    private RateLimitService rateLimitService;

    private final RestTemplate restTemplate = HttpClientSupport.newRestTemplate();

    @Override
    public AmapResponse apply(AmapRequest request) {
        if (!enabled) {
            return AmapResponse.fallback("高德地图服务已禁用");
        }

        if (apiKey == null || apiKey.isBlank()) {
            return AmapResponse.fallback("高德地图API密钥未配置");
        }

        try {
            acquireRateLimit();

            return switch (request.getAction()) {
                case "geocode" -> doGeocode(request);
                case "regeo" -> doRegeo(request);
                case "poi" -> doPoiSearch(request);
                case "inputtips" -> doInputtips(request);
                case "walking" -> doWalking(request);
                case "transit" -> doTransit(request);
                case "driving" -> doDriving(request);
                case "bicycling" -> doBicycling(request);
                case "distance" -> doDistance(request);
                default -> AmapResponse.fallback("未知的操作类型: " + request.getAction());
            };
        } catch (RateLimitException e) {
            // 不再吞异常，让 RateLimitException 向上传播到 GlobalExceptionHandler
            throw e;
        } catch (Exception e) {
            log.error("高德地图API调用异常", e);
            return AmapResponse.fallback("API调用异常: " + e.getMessage());
        }
    }

    // ==================== 地理编码相关 ====================

    @SuppressWarnings("unchecked")
    private AmapResponse doGeocode(AmapRequest request) {
        String url = UriComponentsBuilder.fromHttpUrl(GEOCODE_URL)
                .queryParam("key", apiKey)
                .queryParam("address", request.getAddress())
                .queryParam("city", request.getCity())
                .build()
                .toUriString();

        log.debug("地理编码请求: {}", request.getAddress());
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);

        if (response == null) {
            return AmapResponse.fallback("地理编码API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback("地理编码失败: " + info);
        }

        List<Map<String, Object>> geocodes = (List<Map<String, Object>>) response.get("geocodes");
        if (geocodes == null || geocodes.isEmpty()) {
            return AmapResponse.builder()
                    .success(true)
                    .action("geocode")
                    .message("未找到该地址的坐标")
                    .build();
        }

        Map<String, Object> firstResult = geocodes.get(0);
        GeocodeResult result = GeocodeResult.builder()
                .location((String) firstResult.get("location"))
                .province((String) firstResult.get("province"))
                .city((String) firstResult.get("city"))
                .district((String) firstResult.get("district"))
                .adcode((String) firstResult.get("adcode"))
                .build();

        return AmapResponse.builder()
                .success(true)
                .action("geocode")
                .count(1)
                .geocodeResult(result)
                .remainingCalls(getRemaining())
                .build();
    }

    @SuppressWarnings("unchecked")
    private AmapResponse doRegeo(AmapRequest request) {
        String url = UriComponentsBuilder.fromHttpUrl(REGEO_URL)
                .queryParam("key", apiKey)
                .queryParam("location", request.getLocation())
                .queryParam("extensions", request.getExtensions() != null ? request.getExtensions() : "all")
                .build()
                .toUriString();

        log.debug("逆地理编码请求: {}", request.getLocation());
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);

        if (response == null) {
            return AmapResponse.fallback("逆地理编码API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback("逆地理编码失败: " + info);
        }

        Map<String, Object> regeo = (Map<String, Object>) response.get("regeocode");
        if (regeo == null) {
            return AmapResponse.fallback("未找到该坐标对应的地址");
        }

        Map<String, Object> addressComponent = (Map<String, Object>) regeo.get("addressComponent");
        RegeoResult result = RegeoResult.builder()
                .formattedAddress((String) regeo.get("formatted_address"))
                .province(addressComponent != null ? (String) addressComponent.get("province") : null)
                .city(addressComponent != null ? (String) addressComponent.get("city") : null)
                .district(addressComponent != null ? (String) addressComponent.get("district") : null)
                .adcode(addressComponent != null ? (String) addressComponent.get("adcode") : null)
                .township(addressComponent != null ? (String) addressComponent.get("township") : null)
                .streetNumber(regeo.get("streetNumber") != null ?
                        (String) ((Map<String, Object>) regeo.get("streetNumber")).get("street") : null)
                .build();

        return AmapResponse.builder()
                .success(true)
                .action("regeo")
                .count(1)
                .regeoResult(result)
                .remainingCalls(getRemaining())
                .build();
    }

    // ==================== POI搜索 ====================

    @SuppressWarnings("unchecked")
    private AmapResponse doPoiSearch(AmapRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(POI_SEARCH_URL)
                .queryParam("key", apiKey)
                .queryParam("keywords", request.getKeywords())
                .queryParam("types", request.getTypes())
                .queryParam("city", request.getCity())
                .queryParam("citylimit", request.getCitylimit() != null ? request.getCitylimit() : true)
                .queryParam("offset", request.getOffset() != null ? request.getOffset() : 20)
                .queryParam("page", request.getPage() != null ? request.getPage() : 1);

        String url = builder.build().toUriString();

        log.debug("POI搜索请求: keywords={}, city={}", request.getKeywords(), request.getCity());
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);

        if (response == null) {
            return AmapResponse.fallback("POI搜索API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback("POI搜索失败: " + info);
        }

        List<Map<String, Object>> pois = (List<Map<String, Object>>) response.get("pois");
        if (pois == null || pois.isEmpty()) {
            return AmapResponse.builder()
                    .success(true)
                    .action("poi")
                    .count(0)
                    .pois(new ArrayList<>())
                    .message("未找到相关POI")
                    .remainingCalls(getRemaining())
                    .build();
        }

        List<PoiInfo> poiList = pois.stream()
                .map(this::parsePoiInfo)
                .collect(Collectors.toList());

        return AmapResponse.builder()
                .success(true)
                .action("poi")
                .count(poiList.size())
                .total(parseInt(response.get("count")))
                .pois(poiList)
                .remainingCalls(getRemaining())
                .build();
    }

    // ==================== 输入提示 ====================

    @SuppressWarnings("unchecked")
    private AmapResponse doInputtips(AmapRequest request) {
        if (request.getKeywords() == null || request.getKeywords().isBlank()) {
            return AmapResponse.fallback("输入提示需要提供关键词(keywords)");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(INPUTTIPS_URL)
                .queryParam("key", apiKey)
                .queryParam("keywords", request.getKeywords())
                .queryParam("city", request.getCity())
                .queryParam("datatype", request.getDatatype() != null ? request.getDatatype() : "poi")
                .queryParam("location", request.getLocation());

        String url = builder.build().toUriString();

        log.debug("输入提示请求: keywords={}, city={}", request.getKeywords(), request.getCity());
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);

        if (response == null) {
            return AmapResponse.fallback("输入提示API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback("输入提示失败: " + info);
        }

        List<Map<String, Object>> tips = (List<Map<String, Object>>) response.get("tips");
        if (tips == null || tips.isEmpty()) {
            return AmapResponse.builder()
                    .success(true)
                    .action("inputtips")
                    .count(0)
                    .inputtips(new ArrayList<>())
                    .message("未找到相关提示")
                    .remainingCalls(getRemaining())
                    .build();
        }

        List<InputTip> inputTips = tips.stream()
                .map(this::parseInputTip)
                .collect(Collectors.toList());

        return AmapResponse.builder()
                .success(true)
                .action("inputtips")
                .count(inputTips.size())
                .inputtips(inputTips)
                .remainingCalls(getRemaining())
                .build();
    }

    @SuppressWarnings("unchecked")
    private InputTip parseInputTip(Map<String, Object> tip) {
        return InputTip.builder()
                .id((String) tip.get("id"))
                .name((String) tip.get("name"))
                .district((String) tip.get("district"))
                .adcode((String) tip.get("adcode"))
                .location((String) tip.get("location"))
                .address((String) tip.get("address"))
                .typecode((String) tip.get("typecode"))
                .build();
    }

    // ==================== 路径规划 ====================

    @SuppressWarnings("unchecked")
    private AmapResponse doWalking(AmapRequest request) {
        if (request.getOrigin() == null || request.getDestination() == null) {
            return AmapResponse.fallback("步行规划需要提供起点(origin)和终点(destination)坐标");
        }

        String url = UriComponentsBuilder.fromHttpUrl(WALKING_URL)
                .queryParam("key", apiKey)
                .queryParam("origin", request.getOrigin())
                .queryParam("destination", request.getDestination())
                .build()
                .toUriString();

        log.debug("步行路径规划请求: {} -> {}", request.getOrigin(), request.getDestination());
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);

        return parseDirectionResponse(response, "walking");
    }

    @SuppressWarnings("unchecked")
    private AmapResponse doTransit(AmapRequest request) {
        if (request.getOrigin() == null || request.getDestination() == null || request.getCity() == null) {
            return AmapResponse.fallback("公交规划需要提供起点(origin)、终点(destination)和城市(city)");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(TRANSIT_URL)
                .queryParam("key", apiKey)
                .queryParam("origin", request.getOrigin())
                .queryParam("destination", request.getDestination())
                .queryParam("city", request.getCity())
                .queryParam("strategy", request.getStrategy() != null ? request.getStrategy() : 0)
                .queryParam("extensions", request.getExtensions() != null ? request.getExtensions() : "base")
                .queryParam("nightflag", request.getNightflag() != null ? request.getNightflag() : 0);

        String url = builder.build().toUriString();

        log.debug("公交路径规划请求: {} -> {}, city={}", request.getOrigin(), request.getDestination(), request.getCity());
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);

        return parseTransitResponse(response);
    }

    @SuppressWarnings("unchecked")
    private AmapResponse doDriving(AmapRequest request) {
        if (request.getOrigin() == null || request.getDestination() == null) {
            return AmapResponse.fallback("驾车规划需要提供起点(origin)和终点(destination)坐标");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(DRIVING_URL)
                .queryParam("key", apiKey)
                .queryParam("origin", request.getOrigin())
                .queryParam("destination", request.getDestination())
                .queryParam("extensions", request.getExtensions() != null ? request.getExtensions() : "base")
                .queryParam("strategy", request.getStrategy() != null ? request.getStrategy() : 0);

        if (request.getWaypoints() != null) {
            builder.queryParam("waypoints", request.getWaypoints());
        }
        if (request.getProvince() != null) {
            builder.queryParam("province", request.getProvince());
        }
        if (request.getNumber() != null) {
            builder.queryParam("number", request.getNumber());
        }

        String url = builder.build().toUriString();

        log.debug("驾车路径规划请求: {} -> {}", request.getOrigin(), request.getDestination());
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);

        return parseDirectionResponse(response, "driving");
    }

    @SuppressWarnings("unchecked")
    private AmapResponse doBicycling(AmapRequest request) {
        if (request.getOrigin() == null || request.getDestination() == null) {
            return AmapResponse.fallback("骑行规划需要提供起点(origin)和终点(destination)坐标");
        }

        String url = UriComponentsBuilder.fromHttpUrl(BICYCLING_URL)
                .queryParam("key", apiKey)
                .queryParam("origin", request.getOrigin())
                .queryParam("destination", request.getDestination())
                .build()
                .toUriString();

        log.debug("骑行路径规划请求: {} -> {}", request.getOrigin(), request.getDestination());
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);

        return parseDirectionResponse(response, "bicycling");
    }

    // ==================== 距离测量 ====================

    @SuppressWarnings("unchecked")
    private AmapResponse doDistance(AmapRequest request) {
        if (request.getOrigins() == null || request.getDestination() == null) {
            return AmapResponse.fallback("距离测量需要提供起点(origins)和终点(destination)");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(DISTANCE_URL)
                .queryParam("key", apiKey)
                .queryParam("origins", request.getOrigins())
                .queryParam("destination", request.getDestination())
                .queryParam("type", request.getType() != null ? request.getType() : 1);

        String url = builder.build().toUriString();

        log.debug("距离测量请求: {} -> {}", request.getOrigins(), request.getDestination());
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);

        if (response == null) {
            return AmapResponse.fallback("距离测量API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback("距离测量失败: " + info);
        }

        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        if (results == null || results.isEmpty()) {
            return AmapResponse.builder()
                    .success(true)
                    .action("distance")
                    .count(0)
                    .message("未找到距离数据")
                    .remainingCalls(getRemaining())
                    .build();
        }

        List<DistanceResult> distanceResults = results.stream()
                .map(r -> DistanceResult.builder()
                        .origin((String) r.get("origin"))
                        .destination((String) r.get("destination"))
                        .distance(parseInt(r.get("distance")))
                        .duration(parseInt(r.get("duration")))
                        .build())
                .collect(Collectors.toList());

        return AmapResponse.builder()
                .success(true)
                .action("distance")
                .count(distanceResults.size())
                .distanceResults(distanceResults)
                .remainingCalls(getRemaining())
                .build();
    }

    // ==================== 响应解析工具方法 ====================

    @SuppressWarnings("unchecked")
    private AmapResponse parseDirectionResponse(Map<String, Object> response, String action) {
        if (response == null) {
            return AmapResponse.fallback(action + "路径规划API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback(action + "路径规划失败: " + info);
        }

        String routeKey = action.equals("bicycling") ? "data" : "route";
        Map<String, Object> route = (Map<String, Object>) response.get(routeKey);
        if (route == null) {
            return AmapResponse.builder()
                    .success(true)
                    .action(action)
                    .count(0)
                    .message("未找到路径数据")
                    .remainingCalls(getRemaining())
                    .build();
        }

        List<PathResult> paths = new ArrayList<>();

        if (action.equals("walking") || action.equals("driving") || action.equals("bicycling")) {
            Object pathsObj = route.get("paths");
            if (pathsObj instanceof List) {
                List<Map<String, Object>> pathsList = (List<Map<String, Object>>) pathsObj;
                for (Map<String, Object> pathMap : pathsList) {
                    paths.add(parsePathResult(pathMap, action));
                }
            }
        }

        return AmapResponse.builder()
                .success(true)
                .action(action)
                .count(paths.size())
                .paths(paths)
                .remainingCalls(getRemaining())
                .build();
    }

    @SuppressWarnings("unchecked")
    private PathResult parsePathResult(Map<String, Object> pathMap, String action) {
        PathResult.PathResultBuilder builder = PathResult.builder()
                .distance(parseInt(pathMap.get("distance")))
                .duration(parseInt(pathMap.get("duration")));

        if (action.equals("driving")) {
            builder.tolls(parseInt(pathMap.get("tolls")));
            builder.tollDistance(parseInt(pathMap.get("toll_distance")));
        }

        List<Map<String, Object>> stepsList = (List<Map<String, Object>>) pathMap.get("steps");
        if (stepsList != null) {
            List<PathStep> steps = stepsList.stream()
                    .map(step -> PathStep.builder()
                            .instruction((String) step.get("instruction"))
                            .road((String) step.get("road"))
                            .distance(parseInt(step.get("distance")))
                            .duration(parseInt(step.get("duration")))
                            .polyline((String) step.get("polyline"))
                            .action((String) step.get("action"))
                            .build())
                    .collect(Collectors.toList());
            builder.steps(steps);
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private AmapResponse parseTransitResponse(Map<String, Object> response) {
        if (response == null) {
            return AmapResponse.fallback("公交路径规划API返回空数据");
        }

        String status = (String) response.get("status");
        if (!"1".equals(status)) {
            String info = (String) response.get("info");
            return AmapResponse.fallback("公交路径规划失败: " + info);
        }

        Map<String, Object> route = (Map<String, Object>) response.get("route");
        if (route == null) {
            return AmapResponse.builder()
                    .success(true)
                    .action("transit")
                    .count(0)
                    .message("未找到公交路径数据")
                    .remainingCalls(getRemaining())
                    .build();
        }

        List<TransitResult> transits = new ArrayList<>();
        List<Map<String, Object>> transitsList = (List<Map<String, Object>>) route.get("transits");
        if (transitsList != null) {
            for (Map<String, Object> transitMap : transitsList) {
                TransitResult.TransitResultBuilder tBuilder = TransitResult.builder()
                        .cost(parseInt(transitMap.get("cost")))
                        .duration(parseInt(transitMap.get("duration")))
                        .walkingDistance(parseInt(transitMap.get("walking_distance")))
                        .nightflag(parseInt(transitMap.get("nightflag")));

                // 解析 segments
                List<Map<String, Object>> segmentsList = (List<Map<String, Object>>) transitMap.get("segments");
                if (segmentsList != null) {
                    List<TransitSegment> segments = segmentsList.stream()
                            .map(seg -> {
                                TransitSegment.TransitSegmentBuilder sBuilder = TransitSegment.builder();

                                // 步行段
                                Map<String, Object> walking = (Map<String, Object>) seg.get("walking");
                                if (walking != null) {
                                    sBuilder.walkingDistance(parseInt(walking.get("distance")));
                                    sBuilder.walkingDuration(parseInt(walking.get("duration")));
                                }

                                // 公交段
                                List<Map<String, Object>> buses = (List<Map<String, Object>>) seg.get("bus");
                                if (buses != null && !buses.isEmpty()) {
                                    Map<String, Object> bus = buses.get(0);
                                    sBuilder.busName((String) ((Map<String, Object>) bus.get("buslines")).get("name"));
                                    sBuilder.busType((String) ((Map<String, Object>) bus.get("buslines")).get("type"));
                                }

                                return sBuilder.build();
                            })
                            .collect(Collectors.toList());
                    tBuilder.segments(segments);
                }

                transits.add(tBuilder.build());
            }
        }

        return AmapResponse.builder()
                .success(true)
                .action("transit")
                .count(transits.size())
                .transits(transits)
                .remainingCalls(getRemaining())
                .build();
    }

    @SuppressWarnings("unchecked")
    private PoiInfo parsePoiInfo(Map<String, Object> poi) {
        return PoiInfo.builder()
                .name((String) poi.get("name"))
                .location((String) poi.get("location"))
                .address((String) poi.get("address"))
                .type((String) poi.get("type"))
                .typecode((String) poi.get("typecode"))
                .tel(toString(poi.get("tel")))
                .pname((String) poi.get("pname"))
                .cityname((String) poi.get("cityname"))
                .adname((String) poi.get("adname"))
                .build();
    }

    private String toString(Object value) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        if (value instanceof List) return String.join(", ", ((List<?>) value).stream().map(Object::toString).toList());
        return value.toString();
    }

    private void acquireRateLimit() {
        if (rateLimitService != null) {
            rateLimitService.tryAcquire(SERVICE_NAME);
        }
    }

    private Integer getRemaining() {
        return rateLimitService != null ? rateLimitService.getRemaining(SERVICE_NAME) : null;
    }

    private Integer parseInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Integer) return (Integer) value;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== 请求响应类 ====================

    /**
     * 高德地图统一请求类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AmapRequest {
        /**
         * 操作类型: geocode, regeo, poi, inputtips, walking, transit, driving, bicycling, distance
         */
        private String action;

        // 地理编码参数
        private String address;
        private String city;

        // 逆地理编码参数
        private String location;
        private String extensions;

        // POI搜索参数
        private String keywords;
        private String types;
        private Boolean citylimit;
        private Integer offset;
        private Integer page;

        // 输入提示参数
        private String datatype;

        // 路径规划参数
        private String origin;
        private String destination;
        private String waypoints;
        private String province;
        private String number;
        private Integer nightflag;
        private Integer strategy;

        // 距离测量参数
        private String origins;
        private Integer type;
    }

    /**
     * 高德地图统一响应类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AmapResponse {
        private boolean success;
        private String action;
        private String message;
        private int count;
        private int total;
        private Integer remainingCalls;

        // 地理编码结果
        private GeocodeResult geocodeResult;

        // 逆地理编码结果
        private RegeoResult regeoResult;

        // POI搜索结果
        private List<PoiInfo> pois;

        // 输入提示结果
        private List<InputTip> inputtips;

        // 路径规划结果
        private List<PathResult> paths;
        private List<TransitResult> transits;

        // 距离测量结果
        private List<DistanceResult> distanceResults;

        public static AmapResponse fallback(String message) {
            return AmapResponse.builder()
                    .success(false)
                    .message(message)
                    .count(0)
                    .pois(new ArrayList<>())
                    .inputtips(new ArrayList<>())
                    .build();
        }
    }

    // ==================== 地理编码结果类 ====================

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

    // ==================== 路径规划结果类 ====================

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

    // ==================== 距离测量结果类 ====================

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

    // ==================== 输入提示结果类 ====================

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
