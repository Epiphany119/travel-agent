package com.travel.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.common.tool.PoiTool;
import com.travel.common.tool.WeatherTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 旅行规划服务
 * 使用 Spring AI Function Calling 机制获取实时天气和POI数据
 */
@Slf4j
@Service("agentTravelPlanningService")
@RequiredArgsConstructor
public class TravelPlanningService {

    private final ChatModel chatModel;
    private final WeatherTool weatherTool;
    private final PoiTool poiTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 生成旅行行程
     * 
     * @param userRequest 用户旅行需求
     * @return 生成的行程
     */
    public String generateItinerary(String userRequest) {
        log.info("开始生成兼容接口行程，用户请求长度: {}", userRequest == null ? 0 : userRequest.length());

        String systemPrompt =
                "你是一个专业的旅行规划助手，擅长根据用户需求和实时数据生成个性化的旅行行程。\n\n" +
                "能力：\n" +
                "1. 可以调用天气查询工具获取目的地的天气预报\n" +
                "2. 可以调用POI搜索工具查找景点、美食、住宿等信息\n" +
                "3. 能够综合天气、景点等信息生成合理的行程安排\n\n" +
                "输出要求：\n" +
                "1. 行程应包含每日的具体活动安排\n" +
                "2. 结合天气预报给出穿衣和出行建议\n" +
                "3. 推荐具体的景点、餐厅、住宿\n" +
                "4. 包含交通建议和时间安排\n\n" +
                "请用简洁清晰的方式输出行程规划，包含：\n" +
                "- 每日行程概览\n" +
                "- 推荐景点及开放时间\n" +
                "- 餐饮推荐\n" +
                "- 住宿建议\n" +
                "- 天气信息和穿着建议。";

        List messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userRequest));
        Prompt prompt = new Prompt(messages);

        try {
            // 使用 ChatModel 进行对话
            // Spring AI M4 版本: 直接使用 ChatModel.call() 方法
            ChatResponse response = chatModel.call(prompt);

            if (response == null || response.getResult() == null) {
                return "抱歉，AI服务暂时不可用，请稍后重试。";
            }

            String content = response.getResult().getOutput().getContent();
            StringBuilder result = new StringBuilder(content);

            // M4 版本: Function Calling 需要升级到更高版本才能支持
            // 当前简化为直接返回 AI 生成的内容

            log.info("行程生成完成");
            return result.toString();

        } catch (Exception e) {
            log.error("生成行程时发生错误", e);
            return "生成行程时发生错误: " + e.getMessage();
        }
    }

    /**
     * 处理工具调用
     * 注意：Spring AI M4版本不支持ToolCall，需要通过函数回调自动处理
     * 这里保留方法签名供后续扩展使用
     */
    private String processToolCall(String toolName, String arguments) {
        log.info("处理工具调用: {}, 参数: {}", toolName, arguments);

        try {
            if ("getWeather".equals(toolName)) {
                WeatherTool.WeatherRequest request = objectMapper.readValue(arguments, WeatherTool.WeatherRequest.class);
                WeatherTool.WeatherResponse response = weatherTool.apply(request);

                if (response.isSuccess()) {
                    return formatWeatherResult(response);
                } else {
                    return "天气查询失败: " + response.getMessage();
                }
            } else if ("searchPOI".equals(toolName)) {
                PoiTool.AmapRequest request = objectMapper.readValue(arguments, PoiTool.AmapRequest.class);
                PoiTool.AmapResponse response = poiTool.apply(request);

                if (response.isSuccess()) {
                    return formatPoiResult(response);
                } else {
                    return "POI搜索失败: " + response.getMessage();
                }
            } else {
                return "未知工具: " + toolName;
            }
        } catch (JsonProcessingException e) {
            log.error("解析工具参数失败", e);
            return "工具参数解析失败: " + e.getMessage();
        }
    }

    /**
     * 格式化天气结果
     */
    private String formatWeatherResult(WeatherTool.WeatherResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(response.getCity()).append("天气预报】\n");

        if (response.getWeatherList() != null) {
            for (WeatherTool.DailyWeather day : response.getWeatherList()) {
                sb.append(String.format("📅 %s | %s~%s℃ | %s | %s风\n",
                        day.getDate(),
                        day.getTempMin(),
                        day.getTempMax(),
                        day.getTextDay(),
                        day.getWindDay()));
            }
        }

        return sb.toString();
    }

    /**
     * 格式化POI结果
     */
    private String formatPoiResult(PoiTool.AmapResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("【搜索结果】共找到 ").append(response.getCount()).append(" 个地点\n\n");

        if (response.getPois() != null) {
            for (PoiTool.PoiInfo poi : response.getPois()) {
                sb.append("📍 ").append(poi.getName()).append("\n");
                sb.append("   地址：").append(poi.getAddress()).append("\n");
                sb.append("   类型：").append(poi.getType()).append("\n");
                if (poi.getTel() != null) {
                    sb.append("   电话：").append(poi.getTel()).append("\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 仅获取天气数据
     */
    public WeatherTool.WeatherResponse getWeather(String city) {
        WeatherTool.WeatherRequest request = new WeatherTool.WeatherRequest();
        request.setCity(city);
        request.setType("7d");
        return weatherTool.apply(request);
    }

    /**
     * 仅搜索POI
     */
    public PoiTool.AmapResponse searchPOI(String keywords, String city) {
        PoiTool.AmapRequest request = new PoiTool.AmapRequest();
        request.setAction("poi");
        request.setKeywords(keywords);
        request.setCity(city);
        request.setOffset(10);
        return poiTool.apply(request);
    }
}
