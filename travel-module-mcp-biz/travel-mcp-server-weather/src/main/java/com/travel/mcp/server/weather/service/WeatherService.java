package com.travel.mcp.server.weather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.mcp.server.weather.model.WeatherResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.util.*;

@Slf4j
@Service
public class WeatherService {

    private static final String WTTR_API_URL = "https://wttr.in/%s?format=j1";

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

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WeatherService(@Qualifier("weatherRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Value("${travel.weather.amap-key:}")
    private String amapKey;

    public WeatherResponse getForecast(String city) {
        if (city == null || city.isBlank()) {
            return WeatherResponse.fallback("城市名称不能为空");
        }

        try {
            return fetchFromWttrIn(city);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                log.warn("天气上游请求超时，已降级: city={}", city);
                return WeatherResponse.fallback("天气上游请求超时");
            }
            log.warn("天气上游暂时不可达，已降级: city={}, causeType={}",
                    city, e.getClass().getSimpleName());
            return WeatherResponse.fallback("天气上游暂时不可用");
        } catch (Exception e) {
            log.error("天气数据处理失败: city={}, causeType={}",
                    city, e.getClass().getSimpleName(), e);
            return WeatherResponse.fallback("天气数据处理失败");
        }
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private WeatherResponse fetchFromWttrIn(String city) throws Exception {
        String pinyin = CITY_PINYIN_MAP.getOrDefault(city, city);
        String url = String.format(WTTR_API_URL, pinyin);
        log.info("调用 wttr.in: {}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<String> rawResp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        String raw = rawResp.getBody();
        log.info("wttr.in 响应长度: {} 字符", raw == null ? 0 : raw.length());

        if (raw == null || raw.isEmpty()) {
            return WeatherResponse.fallback("wttr.in 未返回天气数据");
        }

        JsonNode root = objectMapper.readTree(raw);
        JsonNode weatherNode = root.get("weather");
        if (weatherNode == null || !weatherNode.isArray() || weatherNode.isEmpty()) {
            return WeatherResponse.fallback("wttr.in 未返回天气数据");
        }

        // Collect English descriptions for translation
        List<String> rawList = new ArrayList<>();
        for (JsonNode dayNode : weatherNode) {
            JsonNode hourlyNode = dayNode.get("hourly");
            String desc = null;
            String windDir = null;
            if (hourlyNode != null && hourlyNode.isArray()) {
                for (JsonNode hourNode : hourlyNode) {
                    String timeStr = hourNode.has("time") ? hourNode.get("time").asText() : "";
                    int timeVal = 0;
                    try { timeVal = Integer.parseInt(timeStr); } catch (Exception ignored) {}
                    if (timeVal >= 900 && timeVal <= 1500) {
                        JsonNode descNode = hourNode.get("weatherDesc");
                        if (descNode != null && descNode.isArray() && !descNode.isEmpty()) {
                            desc = descNode.get(0).get("value").asText();
                        }
                        JsonNode wdNode = hourNode.get("winddir16Point");
                        windDir = wdNode != null ? wdNode.asText() : null;
                        if (timeVal == 1200) break;
                    }
                }
            }
            if (desc != null && !desc.isBlank()) rawList.add(desc);
            if (windDir != null && !windDir.isBlank()) rawList.add(windDir);
        }

        Map<String, String> translateCache = translateBatch(rawList);

        List<WeatherResponse.DailyWeather> forecast = new ArrayList<>();
        for (JsonNode dayNode : weatherNode) {
            String maxStr = dayNode.has("maxtempC") ? dayNode.get("maxtempC").asText() : null;
            String minStr = dayNode.has("mintempC") ? dayNode.get("mintempC").asText() : null;

            JsonNode hourlyNode = dayNode.get("hourly");
            String desc = null;
            String windDir = null;
            Integer humidity = null;
            Double precip = null;
            Integer uvIndex = null;
            if (hourlyNode != null && hourlyNode.isArray()) {
                for (JsonNode hourNode : hourlyNode) {
                    String timeStr = hourNode.has("time") ? hourNode.get("time").asText() : "";
                    int timeVal = 0;
                    try { timeVal = Integer.parseInt(timeStr); } catch (Exception ignored) {}
                    if (timeVal >= 900 && timeVal <= 1500) {
                        JsonNode descNode = hourNode.get("weatherDesc");
                        if (descNode != null && descNode.isArray() && !descNode.isEmpty()) {
                            desc = descNode.get(0).get("value").asText();
                        }
                        JsonNode wdNode = hourNode.get("winddir16Point");
                        windDir = wdNode != null ? wdNode.asText() : null;
                        JsonNode humNode = hourNode.get("humidity");
                        humidity = humNode != null && !humNode.isNull() ? humNode.asInt() : null;
                        JsonNode prNode = hourNode.get("precipInches");
                        precip = prNode != null && !prNode.isNull() ? prNode.asDouble() : null;
                        JsonNode uvNode = hourNode.get("uvIndex");
                        uvIndex = uvNode != null && !uvNode.isNull() ? uvNode.asInt() : null;
                        if (timeVal == 1200) break;
                    }
                }
            }

            forecast.add(WeatherResponse.DailyWeather.builder()
                    .date(dayNode.get("date").asText())
                    .tempMin(parseInt(minStr))
                    .tempMax(parseInt(maxStr))
                    .text(translate(desc, translateCache))
                    .wind(translate(windDir, translateCache))
                    .humidity(humidity)
                    .precip(precip)
                    .uvIndex(uvIndex)
                    .build());
        }

        return WeatherResponse.builder()
                .success(true)
                .city(city)
                .forecast(forecast)
                .build();
    }

    private Map<String, String> translateBatch(List<String> englishTexts) {
        Map<String, String> result = new HashMap<>();
        if (englishTexts == null || englishTexts.isEmpty()) return result;
        for (String s : englishTexts) {
            if (s != null && !s.isBlank()) {
                result.put(s, fallbackLocal(s.trim()));
            }
        }
        return result;
    }

    private String translate(String en, Map<String, String> cache) {
        if (en == null) return null;
        String key = en.trim();
        if (key.isEmpty()) return key;
        String cached = cache.get(key);
        if (cached != null) return cached;
        return fallbackLocal(key);
    }

    private String fallbackLocal(String en) {
        if (en == null) return null;
        String trimmed = en.trim();
        if (trimmed.isEmpty()) return trimmed;
        if (WEATHER_DESC_ZH.containsKey(trimmed)) return WEATHER_DESC_ZH.get(trimmed);
        if (WEATHER_DESC_ZH.containsKey(en)) return WEATHER_DESC_ZH.get(en);
        for (Map.Entry<String, String> e : WEATHER_DESC_ZH.entrySet()) {
            String k = e.getKey();
            if (trimmed.startsWith(k) && !k.isEmpty()) {
                return trimmed.replaceFirst(java.util.regex.Matcher.quoteReplacement(k), e.getValue()).trim();
            }
        }
        for (Map.Entry<String, String> e : WEATHER_DESC_ZH.entrySet()) {
            String k = e.getKey();
            if (k.isEmpty()) continue;
            if (trimmed.contains(k)) {
                return trimmed.replace(k, e.getValue()).trim();
            }
        }
        String[] tokens = trimmed.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            String zh = WORD_ZH.get(t.toLowerCase());
            sb.append(zh != null ? zh : t).append(" ");
        }
        return sb.toString().trim();
    }

    private Integer parseInt(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return null; }
    }
}
