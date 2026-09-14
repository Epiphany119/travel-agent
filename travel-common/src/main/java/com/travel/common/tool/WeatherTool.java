package com.travel.common.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.travel.common.exception.RateLimitException;
import com.travel.common.http.HttpClientSupport;
import com.travel.common.ratelimit.RateLimitService;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.Collections;

/**
 * 天气查询工具 - 主要使用 wttr.in（无需 key），支持中文城市名
 * 支持查询实时天气和未来天气预报，带熔断器保护
 */
@Slf4j
@Component
public class WeatherTool implements Function<WeatherTool.WeatherRequest, WeatherTool.WeatherResponse> {

    private static final String WEATHER_API_URL = "https://devapi.qweather.com/v7/weather";
    private static final String GEO_API_URL = "https://geoapi.qweather.com/v2/city/lookup";
    private static final String SERVICE_NAME = "weather";

    // 常用城市 ID 映射，Geo API 不可用时的兜底
    private static final Map<String, String> CITY_LOCATION_MAP = Map.ofEntries(
            Map.entry("北京", "101010100"),
            Map.entry("上海", "101020100"),
            Map.entry("广州", "101280101"),
            Map.entry("深圳", "101280601"),
            Map.entry("杭州", "101210101"),
            Map.entry("成都", "101270101"),
            Map.entry("重庆", "101040000"),
            Map.entry("武汉", "101200101"),
            Map.entry("西安", "101110101"),
            Map.entry("南京", "101190101"),
            Map.entry("苏州", "101190401"),
            Map.entry("天津", "101030000"),
            Map.entry("郑州", "101180101"),
            Map.entry("长沙", "101250101"),
            Map.entry("青岛", "101120201"),
            Map.entry("济南", "101120601"),
            Map.entry("福州", "101230101"),
            Map.entry("厦门", "101230201"),
            Map.entry("珠海", "101280701"),
            Map.entry("东莞", "101281601"),
            Map.entry("佛山", "101280800"),
            Map.entry("宁波", "101210401"),
            Map.entry("无锡", "101190201"),
            Map.entry("合肥", "101220101"),
            Map.entry("昆明", "101290101"),
            Map.entry("沈阳", "101070101"),
            Map.entry("大连", "101070201"),
            Map.entry("哈尔滨", "101050101"),
            Map.entry("长春", "101060101"),
            Map.entry("石家庄", "101090101"),
            Map.entry("南昌", "101240101"),
            Map.entry("贵阳", "101260101"),
            Map.entry("太原", "101100101"),
            Map.entry("南宁", "101300101"),
            Map.entry("海口", "101310101"),
            Map.entry("三亚", "101310201"),
            Map.entry("兰州", "101160101"),
            Map.entry("银川", "101170101"),
            Map.entry("西宁", "101150101"),
            Map.entry("拉萨", "101140101"),
            Map.entry("乌鲁木齐", "101130101"),
            Map.entry("呼和浩特", "101080101"),
            Map.entry("香港", "101320101"),
            Map.entry("澳门", "101330101"),
            Map.entry("台北", "101340101")
    );

    // 中文城市名 → 拼音（wttr.in 不认中文，直接用拼音查询更准）
    private static final Map<String, String> CITY_PINYIN_MAP = Map.ofEntries(
            Map.entry("北京", "Beijing"),
            Map.entry("上海", "Shanghai"),
            Map.entry("广州", "Guangzhou"),
            Map.entry("深圳", "Shenzhen"),
            Map.entry("杭州", "Hangzhou"),
            Map.entry("成都", "Chengdu"),
            Map.entry("重庆", "Chongqing"),
            Map.entry("武汉", "Wuhan"),
            Map.entry("西安", "Xian"),
            Map.entry("南京", "Nanjing"),
            Map.entry("苏州", "Suzhou"),
            Map.entry("天津", "Tianjin"),
            Map.entry("郑州", "Zhengzhou"),
            Map.entry("长沙", "Changsha"),
            Map.entry("青岛", "Qingdao"),
            Map.entry("济南", "Jinan"),
            Map.entry("福州", "Fuzhou"),
            Map.entry("厦门", "Xiamen"),
            Map.entry("珠海", "Zhuhai"),
            Map.entry("东莞", "Dongguan"),
            Map.entry("佛山", "Foshan"),
            Map.entry("宁波", "Ningbo"),
            Map.entry("无锡", "Wuxi"),
            Map.entry("合肥", "Hefei"),
            Map.entry("昆明", "Kunming"),
            Map.entry("沈阳", "Shenyang"),
            Map.entry("大连", "Dalian"),
            Map.entry("哈尔滨", "Harbin"),
            Map.entry("长春", "Changchun"),
            Map.entry("石家庄", "Shijiazhuang"),
            Map.entry("南昌", "Nanchang"),
            Map.entry("贵阳", "Guiyang"),
            Map.entry("太原", "Taiyuan"),
            Map.entry("南宁", "Nanning"),
            Map.entry("海口", "Haikou"),
            Map.entry("三亚", "Sanya"),
            Map.entry("兰州", "Lanzhou"),
            Map.entry("银川", "Yinchuan"),
            Map.entry("西宁", "Xining"),
            Map.entry("拉萨", "Lhasa"),
            Map.entry("乌鲁木齐", "Urumqi"),
            Map.entry("呼和浩特", "Hohhot"),
            Map.entry("香港", "Hong+Kong"),
            Map.entry("澳门", "Macau"),
            Map.entry("台北", "Taipei")
    );

    // wttr.in 英文天气描述 → 中文（HashMap 避免 Map.ofEntries 重复 key 问题）
    private static final Map<String, String> WEATHER_DESC_ZH;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("Sunny", "晴天");
        m.put("Clear", "晴");
        m.put("Partly Cloudy", "多云");
        m.put("Partly cloudy", "多云");
        m.put("Cloudy", "阴天");
        m.put("Overcast", "阴天");
        m.put("Mist", "薄雾");
        m.put("Fog", "雾");
        m.put("Freezing fog", "冻雾");
        m.put("Light rain", "小雨");
        m.put("Moderate rain", "中雨");
        m.put("Heavy rain", "大雨");
        m.put("Light drizzle", "毛毛雨");
        m.put("Moderate drizzle", "小毛毛雨");
        m.put("Heavy drizzle", "浓毛毛雨");
        m.put("Patchy rain nearby", "局部小雨");
        m.put("Patchy light rain", "局部小雨");
        m.put("Patchy light drizzle", "局部毛毛雨");
        m.put("Patchy moderate rain", "局部中雨");
        m.put("Patchy heavy rain", "局部大雨");
        m.put("Light rain shower", "小阵雨");
        m.put("Moderate or heavy rain shower", "中到大阵雨");
        m.put("Torrential rain shower", "暴阵雨");
        m.put("Torrential rain", "暴雨");
        m.put("Heavy freezing rain", "大冻雨");
        m.put("Light freezing rain", "小冻雨");
        m.put("Moderate or heavy freezing rain", "中到大冻雨");
        m.put("Freezing drizzle", "冻毛毛雨");
        m.put("Heavy freezing drizzle", "大冻毛毛雨");
        m.put("Freezing rain", "冻雨");
        m.put("Thundery outbreaks possible", "可能有雷暴");
        m.put("Patchy light rain with thunder", "局部雷阵雨");
        m.put("Moderate or heavy rain with thunder", "中到大雷阵雨");
        m.put("Patchy light snow with thunder", "局部雷阵雪");
        m.put("Moderate or heavy snow with thunder", "中到大雷阵雪");
        m.put("Light snow", "小雪");
        m.put("Moderate snow", "中雪");
        m.put("Heavy snow", "大雪");
        m.put("Patchy snow possible", "可能有阵雪");
        m.put("Patchy light snow", "局部小雪");
        m.put("Patchy moderate snow", "局部中雪");
        m.put("Patchy heavy snow", "局部大雪");
        m.put("Blowing snow", "吹雪");
        m.put("Blizzard", "暴风雪");
        m.put("Sleet", "雨夹雪");
        m.put("Light sleet", "小雪夹雨");
        m.put("Light sleet showers", "小阵雪夹雨");
        m.put("Moderate or heavy sleet showers", "中到大阵雪夹雨");
        m.put("Patchy sleet nearby", "局部雨夹雪");
        m.put("Ice", "冰");
        m.put("Hail", "冰雹");
        m.put("Ice pellets", "冰粒");
        m.put("Hot", "炎热");
        m.put("Cold", "寒冷");
        m.put("Wind", "大风");
        m.put("Windy", "大风");
        m.put("Dusty", "扬尘");
        m.put("Sand", "沙尘");
        m.put("Smoke", "烟霾");
        m.put("Haze", "霾");
        m.put("Thunder", "雷暴");
        m.put("Smoky haze", "烟霾");
        // wttr.in 实际返回带尾空格的版本（直接覆盖）
        m.put("Sunny ", "晴天");
        m.put("Clear ", "晴");
        m.put("Partly Cloudy ", "多云");
        m.put("Cloudy ", "阴天");
        m.put("Overcast ", "阴天");
        m.put("Mist ", "薄雾");
        m.put("Fog ", "雾");
        m.put("Smoky haze ", "烟霾");
        m.put("Haze ", "霾");
        m.put("Patchy rain nearby ", "局部小雨");
        m.put("Light drizzle ", "毛毛雨");
        m.put("Patchy light drizzle ", "局部毛毛雨");
        m.put("Light rain shower ", "小阵雨");
        m.put("Sand ", "沙尘");
        m.put("Smoke ", "烟霾");
        WEATHER_DESC_ZH = Collections.unmodifiableMap(m);
    }

    // 单词级兜底字典
    private static final Map<String, String> WORD_ZH = Map.ofEntries(
            Map.entry("sunny", "晴"),
            Map.entry("clear", "晴"),
            Map.entry("partly", "局部"),
            Map.entry("cloudy", "多云"),
            Map.entry("overcast", "阴"),
            Map.entry("mist", "薄雾"),
            Map.entry("fog", "雾"),
            Map.entry("freezing", "冻"),
            Map.entry("rain", "雨"),
            Map.entry("drizzle", "毛毛雨"),
            Map.entry("shower", "阵雨"),
            Map.entry("showers", "阵雨"),
            Map.entry("patchy", "局部"),
            Map.entry("nearby", "附近"),
            Map.entry("light", "小"),
            Map.entry("moderate", "中"),
            Map.entry("heavy", "大"),
            Map.entry("torrential", "暴"),
            Map.entry("thunder", "雷"),
            Map.entry("thundery", "雷暴"),
            Map.entry("outbreaks", "爆发"),
            Map.entry("possible", "可能有"),
            Map.entry("snow", "雪"),
            Map.entry("sleet", "雨夹雪"),
            Map.entry("ice", "冰"),
            Map.entry("pellets", "粒"),
            Map.entry("hail", "雹"),
            Map.entry("blizzard", "暴风雪"),
            Map.entry("blowing", "吹"),
            Map.entry("hot", "炎热"),
            Map.entry("cold", "寒冷"),
            Map.entry("wind", "风"),
            Map.entry("windy", "大风"),
            Map.entry("dusty", "扬尘"),
            Map.entry("sand", "沙"),
            Map.entry("smoke", "烟霾"),
            Map.entry("haze", "霾"),
            Map.entry("and", "与"),
            Map.entry("with", "伴随"),
            Map.entry("n", "北风"),
            Map.entry("nne", "东北偏北"),
            Map.entry("ne", "东北"),
            Map.entry("ene", "东北偏东"),
            Map.entry("e", "东风"),
            Map.entry("ese", "东南偏东"),
            Map.entry("se", "东南"),
            Map.entry("sse", "东南偏南"),
            Map.entry("s", "南风"),
            Map.entry("ssw", "西南偏南"),
            Map.entry("sw", "西南"),
            Map.entry("wsw", "西南偏西"),
            Map.entry("w", "西风"),
            Map.entry("wnw", "西北偏西"),
            Map.entry("nw", "西北"),
            Map.entry("nnw", "西北偏北"),
            Map.entry("vrb", "无固定风向")
    );

    private String fallbackLocal(String en) {
        if (en == null) return null;
        String trimmed = en.trim();
        if (trimmed.isEmpty()) return trimmed;
        // 1) 精确匹配（trim 后）
        if (WEATHER_DESC_ZH.containsKey(trimmed)) return WEATHER_DESC_ZH.get(trimmed);
        // 2) 精确匹配（原始）
        if (WEATHER_DESC_ZH.containsKey(en)) return WEATHER_DESC_ZH.get(en);
        // 3) 前缀匹配
        for (Map.Entry<String, String> e : WEATHER_DESC_ZH.entrySet()) {
            String k = e.getKey();
            if (trimmed.startsWith(k) && !k.isEmpty()) {
                return trimmed.replaceFirst(java.util.regex.Matcher.quoteReplacement(k), e.getValue()).trim();
            }
        }
        // 4) 子串匹配
        for (Map.Entry<String, String> e : WEATHER_DESC_ZH.entrySet()) {
            String k = e.getKey();
            if (k.isEmpty()) continue;
            if (trimmed.contains(k)) {
                return trimmed.replace(k, e.getValue()).trim();
            }
        }
        // 5) 单词级拼接
        String[] tokens = trimmed.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            String zh = WORD_ZH.get(t.toLowerCase());
            sb.append(zh != null ? zh : t).append(" ");
        }
        String result = sb.toString().trim();
        if (result.equals(trimmed)) {
            log.warn("本地翻译未覆盖: '{}'", trimmed);
        }
        return result;
    }

    // 智谱翻译开关（默认关闭，使用本地字典）
    @Value("${travel.weather.translate.use-llm:false}")
    private boolean useLlm;

    @Autowired(required = false)
    private org.springframework.ai.chat.model.ChatModel chatModel;

    private java.util.Map<String, String> translateBatch(java.util.List<String> englishTexts) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        if (englishTexts == null || englishTexts.isEmpty()) return result;
        java.util.List<String> uniq = englishTexts.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        if (uniq.isEmpty()) return result;

        // 开关关闭或无 chatModel → 直接本地字典
        if (!useLlm || chatModel == null) {
            for (String s : uniq) result.put(s, fallbackLocal(s));
            return result;
        }

        try {
            StringBuilder numbered = new StringBuilder();
            for (int i = 0; i < uniq.size(); i++) {
                numbered.append(i + 1).append(". ").append(uniq.get(i)).append("\n");
            }
            String userPrompt =
                    "请把以下英文短句逐条翻译成简洁中文（前 N 条是天气描述，3-6 字，如 \"Light rain shower\" → \"小阵雨\"；\n" +
                    "若是 16 方位风向缩写，翻译为中文，如 \"NNE\" → \"东北偏北\"，\"VRB\" → \"无固定风向\"）。\n" +
                    "严格按编号输出，每行格式 \"<编号>. <中文>\"，不要其他文字、不要引号、不要代码块。\n\n" + numbered;
            String sysPrompt = "你是天气翻译助手，输入是 wttr.in 返回的短文本，输出必须是简短中文。";

            org.springframework.ai.chat.messages.SystemMessage sys =
                    new org.springframework.ai.chat.messages.SystemMessage(sysPrompt);
            org.springframework.ai.chat.messages.UserMessage usr =
                    new org.springframework.ai.chat.messages.UserMessage(userPrompt);
            org.springframework.ai.chat.model.ChatResponse resp =
                    chatModel.call(new org.springframework.ai.chat.prompt.Prompt(java.util.List.of(sys, usr)));
            String content = resp.getResult().getOutput().getContent();
            if (content == null || content.isBlank()) {
                for (String s : uniq) result.put(s, fallbackLocal(s));
                return result;
            }

            String[] lines = content.split("\\r?\\n");
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*[\\.、:]\\s*(.+?)\\s*$");
            java.util.Map<Integer, String> indexed = new java.util.HashMap<>();
            for (String line : lines) {
                java.util.regex.Matcher m = p.matcher(line);
                if (m.find()) {
                    indexed.put(Integer.parseInt(m.group(1)),
                            m.group(2).replaceAll("[。.，,！!？?]+$", "").trim());
                }
            }
            for (int i = 0; i < uniq.size(); i++) {
                String zh = indexed.get(i + 1);
                if (zh != null && !zh.isEmpty()) result.put(uniq.get(i), zh);
            }
            // LLM 没覆盖到的条目 → 本地字典兜底
            for (String s : uniq) result.putIfAbsent(s, fallbackLocal(s));
            log.info("智谱清言翻译: 入参 {} 条, 命中 {} 条", uniq.size(),
                    uniq.stream().mapToInt(s -> !result.get(s).equals(s) ? 1 : 0).sum());
        } catch (Exception e) {
            log.warn("智谱翻译失败(回退本地字典): {}", e.getMessage());
            for (String s : uniq) result.put(s, fallbackLocal(s));
        }
        return result;
    }

    private String translate(String en, java.util.Map<String, String> cache) {
        if (en == null) return null;
        String key = en.trim();
        if (key.isEmpty()) return key;
        String cached = cache.get(key);
        if (cached != null) return cached;
        return fallbackLocal(key);
    }

    @Value("${qweather.api-key:}")
    private String apiKey;

    @Value("${qweather.enabled:true}")
    private boolean enabled;

    @Value("${qweather.daily-limit:800}")
    private int dailyLimit;

    @Autowired(required = false)
    private RateLimitService rateLimitService;

    private final RestTemplate restTemplate = HttpClientSupport.newRestTemplate();

    @Override
    public WeatherResponse apply(WeatherRequest request) {
        if (!checkRateLimit()) {
            return WeatherResponse.fallback("天气服务今日调用次数已达上限，请明天再试");
        }

        if (!enabled) {
            return WeatherResponse.fallback("天气查询功能已禁用");
        }

        try {
            acquireRateLimit();
            return fetchFromWttrIn(request.getCity());
        } catch (RateLimitException e) {
            // 不再吞异常，让 RateLimitException 向上传播到 GlobalExceptionHandler
            throw e;
        } catch (Exception e) {
            log.error("wttr.in 天气查询失败: {}", e.getMessage(), e);
            return WeatherResponse.fallback("天气查询失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private WeatherResponse fetchFromWttrIn(String city) throws Exception {
        String pinyin = CITY_PINYIN_MAP.getOrDefault(city, city);
        String url = "https://wttr.in/" + pinyin + "?format=j1";
        log.info("调用 wttr.in: {}", url);

        org.springframework.web.client.RestTemplate wttrTemplate = HttpClientSupport.newRestTemplate();
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Accept", "application/json");
        org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);

        org.springframework.http.ResponseEntity<String> rawResp = wttrTemplate.exchange(
                url, org.springframework.http.HttpMethod.GET, entity, String.class);
        String raw = rawResp.getBody();
        log.info("wttr.in 响应长度: {} 字符", raw == null ? 0 : raw.length());

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(raw);
        com.fasterxml.jackson.databind.JsonNode weatherNode = root.get("weather");
        if (weatherNode == null || !weatherNode.isArray() || weatherNode.isEmpty()) {
            return WeatherResponse.fallback("wttr.in 未返回天气数据");
        }

        // 第一遍：收集所有英文描述
        java.util.List<String> rawList = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode dayNode : weatherNode) {
            com.fasterxml.jackson.databind.JsonNode hourlyNode = dayNode.get("hourly");
            String desc = null;
            String windDir = null;
            if (hourlyNode != null && hourlyNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode hourNode : hourlyNode) {
                    String timeStr = hourNode.has("time") ? hourNode.get("time").asText() : "";
                    int timeVal = 0;
                    try { timeVal = Integer.parseInt(timeStr); } catch (Exception ignored) {}
                    if (timeVal >= 900 && timeVal <= 1500) {
                        com.fasterxml.jackson.databind.JsonNode descNode = hourNode.get("weatherDesc");
                        if (descNode != null && descNode.isArray() && !descNode.isEmpty()) {
                            desc = descNode.get(0).get("value").asText();
                        }
                        com.fasterxml.jackson.databind.JsonNode wdNode = hourNode.get("winddir16Point");
                        windDir = wdNode != null ? wdNode.asText() : null;
                        if (timeVal == 1200) break;
                    }
                }
            }
            if (desc != null && !desc.isBlank()) rawList.add(desc);
            if (windDir != null && !windDir.isBlank()) rawList.add(windDir);
        }

        // 一次性翻译
        java.util.Map<String, String> translateCache = translateBatch(rawList);

        // 第二遍：组装结果
        List<DailyWeather> dailyWeathers = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode dayNode : weatherNode) {
            String maxStr = dayNode.has("maxtempC") ? dayNode.get("maxtempC").asText() : null;
            String minStr = dayNode.has("mintempC") ? dayNode.get("mintempC").asText() : null;

            com.fasterxml.jackson.databind.JsonNode hourlyNode = dayNode.get("hourly");
            String desc = null;
            String windDir = null;
            Integer humidity = null;
            Double precip = null;
            Integer uvIndex = null;
            if (hourlyNode != null && hourlyNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode hourNode : hourlyNode) {
                    String timeStr = hourNode.has("time") ? hourNode.get("time").asText() : "";
                    int timeVal = 0;
                    try { timeVal = Integer.parseInt(timeStr); } catch (Exception ignored) {}
                    if (timeVal >= 900 && timeVal <= 1500) {
                        com.fasterxml.jackson.databind.JsonNode descNode = hourNode.get("weatherDesc");
                        if (descNode != null && descNode.isArray() && !descNode.isEmpty()) {
                            desc = descNode.get(0).get("value").asText();
                        }
                        com.fasterxml.jackson.databind.JsonNode wdNode = hourNode.get("winddir16Point");
                        windDir = wdNode != null ? wdNode.asText() : null;
                        com.fasterxml.jackson.databind.JsonNode humNode = hourNode.get("humidity");
                        humidity = humNode != null && !humNode.isNull() ? humNode.asInt() : null;
                        com.fasterxml.jackson.databind.JsonNode prNode = hourNode.get("precipInches");
                        precip = prNode != null && !prNode.isNull() ? prNode.asDouble() : null;
                        com.fasterxml.jackson.databind.JsonNode uvNode = hourNode.get("uvIndex");
                        uvIndex = uvNode != null && !uvNode.isNull() ? uvNode.asInt() : null;
                        if (timeVal == 1200) break;
                    }
                }
            }

            dailyWeathers.add(DailyWeather.builder()
                    .date(dayNode.get("date").asText())
                    .tempMax(parseInt(maxStr))
                    .tempMin(parseInt(minStr))
                    .textDay(translate(desc, translateCache))
                    .textNight(null)
                    .windDay(translate(windDir, translateCache))
                    .windNight(null)
                    .humidity(humidity)
                    .precip(precip)
                    .uvIndex(uvIndex)
                    .build());
        }

        return WeatherResponse.builder()
                .success(true)
                .city(city)
                .weatherList(dailyWeathers)
                .remainingCalls(-1)
                .build();
    }

    private boolean checkRateLimit() {
        if (rateLimitService == null) return true;
        int remaining = rateLimitService.getRemaining(SERVICE_NAME);
        if (remaining <= 0) {
            log.warn("天气服务今日调用次数已用完");
            return false;
        }
        return true;
    }

    private void acquireRateLimit() {
        if (rateLimitService != null) rateLimitService.tryAcquire(SERVICE_NAME);
    }

    private String getLocationId(String city) {
        if (city != null && CITY_LOCATION_MAP.containsKey(city)) {
            log.debug("从本地映射表找到城市 {} 的ID: {}", city, CITY_LOCATION_MAP.get(city));
            return CITY_LOCATION_MAP.get(city);
        }
        try {
            String url = UriComponentsBuilder.fromHttpUrl(GEO_API_URL)
                    .queryParam("location", city)
                    .queryParam("key", apiKey)
                    .build().toUriString();
            log.info("和风天气 Geo API 请求: {}", url);
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            log.info("和风天气 Geo API 响应: {}", response);
            if (response == null) {
                log.warn("Geo API 返回空响应");
                return null;
            }
            Object locationObj = response.get("location");
            if ("200".equals(String.valueOf(response.get("code")))) {
                if (locationObj instanceof List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Map) return (String) ((Map<?, ?>) first).get("id");
                } else if (locationObj instanceof Map) {
                    return (String) ((Map<?, ?>) locationObj).get("id");
                }
            }
            log.warn("Geo API 未找到城市 {}，响应 code: {}, location: {}", city, response.get("code"), locationObj);
        } catch (Exception e) {
            log.warn("Geo API 查询失败（将尝试本地映射表兜底）: {}", e.getMessage());
        }
        if (city != null) {
            for (Map.Entry<String, String> entry : CITY_LOCATION_MAP.entrySet()) {
                if (city.contains(entry.getKey()) || entry.getKey().contains(city)) {
                    log.debug("Geo API 失败，使用模糊匹配: {} -> {}", city, entry.getKey());
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private WeatherResponse parseWeatherResponse(Map<String, Object> response, String city, String requestType) {
        List<Map<String, Object>> weatherList = (List<Map<String, Object>>) response.get("daily");
        if (weatherList == null && "now".equals(requestType) && response.get("now") instanceof Map<?, ?> now) {
            weatherList = new ArrayList<>();
            weatherList.add((Map<String, Object>) now);
        }
        if (weatherList == null || weatherList.isEmpty()) {
            return WeatherResponse.fallback("未获取到天气数据");
        }
        List<DailyWeather> dailyWeathers = weatherList.stream()
                .map(this::parseDailyWeather)
                .collect(Collectors.toList());
        int remaining = rateLimitService != null ? rateLimitService.getRemaining(SERVICE_NAME) : -1;
        return WeatherResponse.builder()
                .success(true).city(city).weatherList(dailyWeathers).remainingCalls(remaining).build();
    }

    @SuppressWarnings("unchecked")
    private DailyWeather parseDailyWeather(Map<String, Object> day) {
        return DailyWeather.builder()
                .date(value(day, "fxDate", "obsTime"))
                .tempMax(parseInt(day.getOrDefault("tempMax", day.get("temp"))))
                .tempMin(parseInt(day.getOrDefault("tempMin", day.get("temp"))))
                .textDay(value(day, "textDay", "text"))
                .textNight(value(day, "textNight", "text"))
                .windDay(value(day, "windDirDay", "windDir"))
                .windNight(value(day, "windDirNight", "windDir"))
                .humidity(parseInt(day.get("humidity")))
                .precip(parseDouble(day.get("precip")))
                .uvIndex(parseInt(day.get("uvIndex")))
                .build();
    }

    private String value(Map<String, Object> source, String primary, String fallback) {
        Object value = source.get(primary) != null ? source.get(primary) : source.get(fallback);
        return value == null ? null : value.toString();
    }

    private Integer parseInt(Object value) {
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        try { return Integer.parseInt(value.toString()); } catch (NumberFormatException e) { return null; }
    }

    private Double parseDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Double) return (Double) value;
        if (value instanceof Integer) return ((Integer) value).doubleValue();
        try { return Double.parseDouble(value.toString()); } catch (NumberFormatException e) { return null; }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeatherRequest {
        private String city;
        @JsonProperty("type")
        private String type = "7d";
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeatherResponse {
        private boolean success;
        private String city;
        private String message;
        private List<DailyWeather> weatherList;
        private Integer remainingCalls;

        public static WeatherResponse fallback(String message) {
            return WeatherResponse.builder().success(false).message(message).build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyWeather {
        private String date;
        private Integer tempMax;
        private Integer tempMin;
        private String textDay;
        private String textNight;
        private String windDay;
        private String windNight;
        private Integer humidity;
        private Double precip;
        private Integer uvIndex;
    }
}
