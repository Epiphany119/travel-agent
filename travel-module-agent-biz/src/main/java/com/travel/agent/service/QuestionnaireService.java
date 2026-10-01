package com.travel.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.travel.agent.persistence.AgentQuestionnaireMapper;
import com.travel.agent.persistence.AgentQuestionnairePO;
import com.travel.common.tool.PoiTool;
import com.travel.common.tool.WeatherTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 驱动的旅行规划服务。
 *
 * <p>核心思路：让智谱 Agent 自己分析用户需求，通过 ReAct 循环自主决定搜什么景点，
 * 最终生成真正定制化的旅行计划。</p>
 *
 * <p>ReAct 循环：
 * 1. Agent 接收用户需求 + 天气数据，思考需要搜哪些关键词
 * 2. 决定调用 search_pois，传递 keywords + city
 * 3. 执行高德 POI 搜索，返回结果给 Agent
 * 4. Agent 拿结果后规划每天行程，输出最终 JSON plan
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionnaireService {

    private final ChatModel chatModel;
    private final WeatherTool weatherTool;
    private final PoiTool poiTool;
    private final AgentQuestionnaireMapper questionnaireMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MAX_REACT_STEPS = 15;
    private static final String TOOL_NAME = "search_pois";

    /** 高德 POI 工具定义 */
    private static final FunctionTool POI_TOOL = new FunctionTool(
            new FunctionTool.Function(
                TOOL_NAME,
                "搜索高德地图景点 POI。根据关键词在指定城市搜索相关地点。\n" +
                "适用场景：找「故宫」「博物馆」「长城」「美食街」等具体景点。\n" +
                "返回：POI 列表（名称、地址、类型、坐标）。",
                "{\"type\":\"object\",\"properties\":{" +
                "\"keywords\":{\"type\":\"string\",\"description\":\"搜索关键词，如 '故宫'、'国家博物馆'、'长城'、'特色美食'\"}," +
                "\"city\":{\"type\":\"string\",\"description\":\"目标城市名，如 '北京'、'杭州'\"}}}," +
                "\"required\":[\"keywords\",\"city\"]}"
            )
    );

    /** 问卷步骤 */
    private static final List<QuestionnaireStep> STEPS = List.of(
            step(0, "destination", "text", "你想去哪里旅行？比如：杭州、成都、上海…", "weather"),
            step(1, "madeAt", "select", "这次你更想要什么旅行节奏？", "none",
                 List.of("轻松漫游", "深度人文", "美食优先", "亲子友好")),
            step(2, "days", "text", "打算玩几天？", "none"),
            step(3, "interests", "text", "对什么主题感兴趣？比如：美食、人文、自然、摄影、购物…(可多个，逗号分隔)", "agent"),
            step(4, "budget", "text", "这次出行总预算大概是多少元？", "none")
    );

    private static QuestionnaireStep step(int idx, String field, String type, String question, String trigger) {
        return QuestionnaireStep.builder()
                .index(idx).field(field).type(type).question(question)
                .apiTrigger(trigger).llmParse(true).build();
    }

    private static QuestionnaireStep step(int idx, String field, String type, String question, String trigger, List<String> options) {
        return QuestionnaireStep.builder()
                .index(idx).field(field).type(type).question(question)
                .apiTrigger(trigger).llmParse(true).options(options).build();
    }

    /** sessionId → 已完成的步骤索引 */
    private final Map<String, Set<Integer>> apiDone = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════
    // 对话 API
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public ObjectNode startSession(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("authenticated user is required");
        }
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        AgentQuestionnairePO po = new AgentQuestionnairePO();
        po.setSessionId(sessionId);
        po.setUserId(userId);
        po.setCurrentStep(0);
        po.setAnswers("{}");
        po.setDataCache("{}");
        po.setStatus("active");
        questionnaireMapper.insert(po);
        apiDone.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());

        QuestionnaireStep first = STEPS.get(0);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sessionId", sessionId);
        node.put("stepIndex", first.getIndex());
        node.put("totalSteps", STEPS.size());
        node.put("question", first.getQuestion());
        node.put("type", first.getType());
        if (first.getOptions() != null) node.set("options", listToArray(first.getOptions()));
        return node;
    }

    public void handleAnswer(String sessionId, int stepIndex, String answer, SseEmitter emitter, String authenticatedUserId) {
        AgentQuestionnairePO po = questionnaireMapper.findBySessionId(sessionId);
        if (po == null) { sendEvent(emitter, "error", Map.of("message", "会话不存在")); emitter.complete(); return; }
        if (authenticatedUserId == null || !authenticatedUserId.equals(po.getUserId())) {
            sendEvent(emitter, "error", Map.of("message", "无权访问该问卷"));
            emitter.complete();
            return;
        }
        if (stepIndex < 0 || stepIndex >= STEPS.size()) { sendEvent(emitter, "error", Map.of("message", "非法步骤")); emitter.complete(); return; }
        if (!"active".equals(po.getStatus())) {
            sendEvent(emitter, "error", Map.of("message", "问卷已结束"));
            emitter.complete();
            return;
        }
        if (stepIndex != po.getCurrentStep()) {
            sendEvent(emitter, "error", Map.of("message", "请按顺序回答问题"));
            emitter.complete();
            return;
        }

        try {
            QuestionnaireStep step = STEPS.get(stepIndex);
            log.info("问卷步骤: sessionId={}, stepIndex={}, field={}", sessionId, stepIndex, step.getField());

            // 1. LLM 规范化
            Map<String, Object> normalized = normalizeStep(step, answer);
            sendEvent(emitter, "parsed", Map.of("field", step.getField(), "value", normalized));

            // 2. 合并到 answers
            Map<String, Object> answers = readMap(po.getAnswers());
            answers.putAll(normalized);
            po.setAnswers(writeMap(answers));
            questionnaireMapper.updateBySessionId(po);

            // 3. 触发 API（仅步骤0查天气）
            if (stepIndex == 0 && !apiDone(sessionId, stepIndex)) {
                InstanceAnswers ans = instanceFromAnswers(answers);
                collectApiData(sessionId, po, ans, emitter);
                markApiDone(sessionId, stepIndex);
            }

            // 4. 推进或触发 Agent 规划
            if (stepIndex + 1 < STEPS.size()) {
                QuestionnaireStep next = STEPS.get(stepIndex + 1);
                po.setCurrentStep(next.getIndex());
                questionnaireMapper.updateBySessionId(po);
                ObjectNode nextNode = objectMapper.createObjectNode();
                nextNode.put("sessionId", sessionId);
                nextNode.put("stepIndex", next.getIndex());
                nextNode.put("totalSteps", STEPS.size());
                nextNode.put("question", next.getQuestion());
                nextNode.put("type", next.getType());
                if (next.getOptions() != null) nextNode.set("options", listToArray(next.getOptions()));
                sendEvent(emitter, "next_question", objectMapper.convertValue(nextNode, JsonNode.class));
            } else {
                // 全部答完 → Agent 规划
                po.setStatus("planning");
                questionnaireMapper.updateBySessionId(po);
                ObjectNode plan = runAgentPlanning(answers, po, emitter);
                po.setStatus("completed");
                questionnaireMapper.updateBySessionId(po);
                sendEvent(emitter, "plan", plan);
            }
            emitter.complete();
        } catch (Exception e) {
            log.error("问卷步骤处理异常: sessionId={}, step={}", sessionId, stepIndex, e);
            sendEvent(emitter, "error", Map.of("message", "处理出错: " + e.getMessage()));
            emitter.complete();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Agent 规划核心：ReAct 循环
    // ═══════════════════════════════════════════════════════════════

    private ObjectNode runAgentPlanning(Map<String, Object> answers, AgentQuestionnairePO po, SseEmitter emitter) {
        InstanceAnswers ans = instanceFromAnswers(answers);
        String userContext = buildAgentContext(ans, po);

        // 对话历史（ReAct 循环消息链）
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(AGENT_SYSTEM_PROMPT));
        messages.add(new UserMessage(userContext));

        // Agent 内存：存储历次 POI 搜索结果，供最终 plan 生成使用
        Map<String, Object> agentMemory = new LinkedHashMap<>();

        for (int step = 0; step < MAX_REACT_STEPS; step++) {
            log.info("[Agent ReAct] Step {}: 发送消息给 Agent，message 数={}", step, messages.size());

            // 带工具定义的 ChatOptions
            ChatOptions opts = org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .withTools(List.of(POI_TOOL))
                    .withTemperature(0.7)
                    .build();

            sendEvent(emitter, "tool_call", Map.of("source", "agent", "action", "Agent 思考中…"));

            ChatResponse resp = chatModel.call(new Prompt(messages, opts));
            String content = resp.getResult().getOutput().getContent();
            String raw = content != null ? content : "";

            log.info("[Agent ReAct] Step {} 响应: {}", step, raw.length() > 300 ? raw.substring(0, 300) + "..." : raw);
            messages.add(new AssistantMessage(raw));

            // 检查 function_call：Agent 是否请求调用工具
            var fc = extractFunctionCall(resp);
            if (fc == null) {
                // Agent 不再请求调用工具，解析 plan
                sendEvent(emitter, "tool_result", Map.of("source", "agent", "ok", true, "summary", "Agent 决策完成"));
                break;
            }

            // 执行工具调用
            String fnName = fc.name() != null ? fc.name() : TOOL_NAME;
            String fnArgs = fc.arguments() != null ? fc.arguments() : "{}";
            String callId = fc.id() != null ? fc.id() : ("call_" + step);

            log.info("[Agent ReAct] 工具调用: name={}, args={}", fnName, fnArgs);
            sendEvent(emitter, "tool_call", Map.of(
                    "source", "poi", "action",
                    "Agent 正在搜索: " + extractKeywordsFromArgs(fnArgs),
                    "args", fnArgs));

            String toolResult;
            try {
                PoiTool.AmapRequest req = parsePoiRequest(fnArgs);
                PoiTool.AmapResponse poiResp = poiTool.apply(req);
                toolResult = objectMapper.writeValueAsString(poiResp);

                // 缓存到 agentMemory
                try {
                    Map<?, ?> resMap = objectMapper.readValue(toolResult, Map.class);
                    agentMemory.put("poi_step_" + step, resMap);
                    Object poisObj = resMap.get("pois");
                    if (poisObj instanceof List<?>) {
                        List<?> pois = (List<?>) poisObj;
                        agentMemory.put("poi_count", ((Number) agentMemory.getOrDefault("poi_count", 0)).intValue() + pois.size());
                    }
                } catch (Exception ignored) {
                }

                sendEvent(emitter, "tool_result", Map.of(
                        "source", "poi", "ok", poiResp.isSuccess(),
                        "summary", poiResp.isSuccess()
                                ? ("获取到 " + poiResp.getCount() + " 个地点")
                                : "POI 获取失败"));
            } catch (Exception e) {
                log.error("[Agent ReAct] 工具执行失败: {}", e.getMessage());
                toolResult = "{\"success\":false,\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                sendEvent(emitter, "tool_result", Map.of("source", "poi", "ok", false, "summary", "工具执行失败: " + e.getMessage()));
            }

            // 把工具结果作为 ToolResponseMessage 喂回 Agent（关键：让 Agent 看到结果再决定下一步）
            messages.add(new ToolResponseMessage(
                    List.of(new ToolResponseMessage.ToolResponse(callId, fnName, toolResult))
            ));
        }

        // 从 Agent 最终消息中解析 JSON plan
        ObjectNode plan = extractPlanFromMessages(messages, ans, agentMemory);
        return plan;
    }

    private String extractKeywordsFromArgs(String args) {
        try {
            Map<?, ?> m = objectMapper.readValue(args, Map.class);
            return str(m.get("keywords"));
        } catch (Exception e) { return args; }
    }

    private PoiTool.AmapRequest parsePoiRequest(String args) throws Exception {
        Map<?, ?> m = objectMapper.readValue(args, Map.class);
        PoiTool.AmapRequest req = new PoiTool.AmapRequest();
        req.setAction("poi");
        req.setKeywords(str(m.get("keywords")));
        req.setCity(str(m.get("city")));
        req.setCitylimit(true);
        req.setOffset(10);
        return req;
    }

    /** 提取 Agent 请求调用的工具（基于 AssistantMessage 的 ToolCall，兼容 Spring AI M4） */
    private org.springframework.ai.chat.messages.AssistantMessage.ToolCall extractFunctionCall(ChatResponse resp) {
        if (resp == null || resp.getResult() == null) return null;
        var output = resp.getResult().getOutput();
        if (output == null) return null;

        // M4：AssistantMessage 持有 List<ToolCall>
        if (output.hasToolCalls() && output.getToolCalls() != null && !output.getToolCalls().isEmpty()) {
            return output.getToolCalls().get(0);
        }

        return null;
    }

    /**
     * 从 Agent 对话历史中提取最终 JSON plan。
     * 在最后几条 Assistant 消息中搜索 JSON 块。
     */
    private ObjectNode extractPlanFromMessages(List<Message> messages, InstanceAnswers ans, Map<String, Object> agentMemory) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (!(msg instanceof AssistantMessage)) continue;
            AssistantMessage am = (AssistantMessage) msg;
            String content = am.getContent();
            if (content == null || content.isBlank()) continue;

            // 提取 JSON 块
            int jsonStart = content.indexOf('{');
            int jsonEnd = content.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String json = content.substring(jsonStart, jsonEnd + 1);
                try {
                    ObjectNode plan = (ObjectNode) objectMapper.readTree(json);
                    enrichPlan(plan, ans);
                    log.info("[Agent] 成功解析 plan，dayPlans={}", plan.get("dayPlans").size());
                    return plan;
                } catch (Exception e) {
                    log.warn("[Agent] JSON 解析失败: {}", e.getMessage());
                }
            }
        }

        log.warn("[Agent] 无法解析 JSON plan，使用 fallback");
        return buildFallbackPlan(ans, agentMemory);
    }

    private void enrichPlan(ObjectNode plan, InstanceAnswers ans) {
        plan.put("destination", nvl(ans.getDestination(), "未知"));
        plan.put("days", Math.max(1, ans.getDays()));
        plan.put("budget", Math.max(100, ans.getBudget()));
        plan.put("madeAt", nvl(ans.getMadeAt(), "轻松漫游"));
        plan.put("interests", nvl(ans.getInterests(), ""));
        plan.put("planId", "TP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        // 统一字段名
        if (!plan.has("dayPlans") && plan.has("days_plan")) {
            plan.set("dayPlans", plan.get("days_plan"));
        }
        if (!plan.has("dayPlans")) {
            plan.putArray("dayPlans");
        }
    }

    private ObjectNode buildFallbackPlan(InstanceAnswers ans, Map<String, Object> agentMemory) {
        ObjectNode plan = objectMapper.createObjectNode();
        enrichPlan(plan, ans);
        plan.put("agentNote", "Agent 响应无法解析，以上为系统辅助生成");

        // 从 agentMemory 提取 POI
        List<Map<String, Object>> allPois = new ArrayList<>();
        for (Object v : agentMemory.values()) {
            if (v instanceof Map<?, ?>) {
                Map<?, ?> m = (Map<?, ?>) v;
                Object poisObj = m.get("pois");
                if (poisObj instanceof List<?>) {
                    for (Object p : (List<?>) poisObj) {
                        if (p instanceof Map<?, ?>) {
                            Map<String, Object> row = new HashMap<>();
                            ((Map<?, ?>) p).forEach((k, val) -> row.put(str(k), val));
                            allPois.add(row);
                        }
                    }
                }
            }
        }

        ArrayNode dayPlans = objectMapper.createArrayNode();
        double[] dayBudgets = distributeBudget(ans.getBudget(), ans.getDays());
        for (int i = 0; i < ans.getDays(); i++) {
            ObjectNode d = objectMapper.createObjectNode();
            d.put("day", i + 1);
            d.put("date", LocalDateTime.now().plusDays(i + 1).format(DATE_FMT));
            d.put("dailyBudget", Math.round(dayBudgets[i]));
            d.put("theme", "第 " + (i + 1) + " 天 · 定制探索");

            ArrayNode activities = objectMapper.createArrayNode();
            String[] times = {"09:00", "14:00", "17:00"};
            for (int j = 0; j < Math.min(3, allPois.size()); j++) {
                int idx = (i * 3 + j) % allPois.size();
                Map<String, Object> poi = allPois.get(idx);
                ObjectNode act = objectMapper.createObjectNode();
                act.put("type", j == 1 ? "meal" : "sightseeing");
                act.put("name", str(poi.get("name")));
                act.put("location", str(poi.get("address")));
                act.put("time", times[j]);
                act.put("duration", 7200);
                act.put("notes", str(poi.get("type")));
                activities.add(act);
            }
            d.set("activities", activities);
            dayPlans.add(d);
        }
        plan.set("dayPlans", dayPlans);
        return plan;
    }

    /** Agent System Prompt：指导 Agent 按 ReAct 方式工作 */
    private static final String AGENT_SYSTEM_PROMPT =
            "你是一位资深旅行规划专家，擅长根据用户需求定制深度旅行计划。\n\n" +
            "你的工作方式（ReAct 模式）：\n" +
            "1. 理解用户需求（目的地、天数、兴趣、预算、节奏）\n" +
            "2. 思考需要搜索哪些景点，使用 search_pois 工具调用高德地图\n" +
            "3. 根据搜索结果，安排每天具体行程（注意地理邻近性和时间节奏）\n" +
            "4. 输出结构化 JSON 旅行计划\n\n" +
            "search_pois 工具：\n" +
            "- name: search_pois\n" +
            "- 输入参数：keywords（搜索关键词，如\"故宫\"）、city（目标城市，如\"北京\"）\n" +
            "- 输出：高德地图 POI 列表（名称、地址、类型、坐标）\n\n" +
            "重要原则：\n" +
            "- 北京人文历史：必搜「故宫」「国家博物馆」「八达岭长城」「天坛」「颐和园」「圆明园」\n" +
            "- 同一区域景点放同一天，减少路上时间\n" +
            "- 博物馆类安排上午，地标景点（故宫）单独半天\n" +
            "- 第一天安排轻松行程（抵达 + 市区），最后一天预留返程\n" +
            "- 每天景点不超过3个，保证深度游览而非走马观花\n\n" +
            "最终必须输出的 JSON（不要用 markdown 包裹，不要有任何额外文字）：\n" +
            "{\n" +
            "  \"title\": \"北京5日人文历史深度游\",\n" +
            "  \"overview\": \"专为...设计的行程...\",\n" +
            "  \"dayPlans\": [\n" +
            "    {\n" +
            "      \"day\": 1,\n" +
            "      \"date\": \"2026-08-25\",\n" +
            "      \"theme\": \"抵达北京 · 初识古都\",\n" +
            "      \"dailyBudget\": 1200,\n" +
            "      \"activities\": [\n" +
            "        {\"type\": \"sightseeing\", \"name\": \"天安门广场\", \"location\": \"北京市东城区西长安街\", \"time\": \"09:00\", \"duration\": 7200, \"notes\": \"世界上最大的城市广场，适合清晨拍照\"},\n" +
            "        {\"type\": \"meal\", \"name\": \"四季民福烤鸭店\", \"location\": \"东城区南锣鼓巷\", \"time\": \"12:00\", \"duration\": 5400, \"notes\": \"北京烤鸭代表餐厅\"},\n" +
            "        {\"type\": \"sightseeing\", \"name\": \"故宫博物院\", \"location\": \"北京市东城区景山前街\", \"time\": \"14:00\", \"duration\": 14400, \"notes\": \"世界文化遗产，需提前预约门票\"}\n" +
            "      ]\n" +
            "    }\n" +
            "  ],\n" +
            "  \"travelTips\": [\"北京景点需提前在官方公众号预约\", \"建议购买北京一卡通方便出行\", \"故宫禁止使用无人机\"],\n" +
            "  \"packingList\": [\"身份证件\", \"舒适的步行鞋\", \"充电宝\", \"防晒用品\"]\n" +
            "}\n\n" +
            "现在请开始工作。先调用 search_pois 搜索用户需要的景点，再输出完整 JSON 计划。";

    private String buildAgentContext(InstanceAnswers ans, AgentQuestionnairePO po) {
        Map<String, Object> cache = readMap(po.getDataCache());
        StringBuilder sb = new StringBuilder();
        sb.append("【用户旅行需求】\n");
        sb.append("- 目的地：").append(nvl(ans.getDestination(), "未填写")).append("\n");
        sb.append("- 旅行节奏：").append(nvl(ans.getMadeAt(), "轻松漫游")).append("\n");
        sb.append("- 游玩天数：").append(ans.getDays() > 0 ? ans.getDays() : 3).append("天\n");
        sb.append("- 兴趣主题：").append(nvl(ans.getInterests(), "未填写")).append("\n");
        sb.append("- 总预算：").append(ans.getBudget() > 0 ? ans.getBudget() : 3000).append("元\n\n");

        // 天气数据
        Object wObj = cache.get("weather");
        if (wObj instanceof Map<?, ?>) {
            Map<?, ?> wm = (Map<?, ?>) wObj;
            Object daysObj = wm.get("days");
            if (Boolean.TRUE.equals(wm.get("success")) && daysObj instanceof List<?>) {
                sb.append("【天气预报】");
                for (Object d : (List<?>) daysObj) {
                    if (d instanceof Map<?, ?>) {
                        Map<?, ?> day = (Map<?, ?>) d;
                        sb.append(day.get("date")).append(" ")
                          .append(day.get("text")).append(" ")
                          .append(day.get("tempMin")).append("~").append(day.get("tempMax")).append("℃；");
                    }
                }
                sb.append("\n");
            } else {
                sb.append("【天气】").append(wm.get("text")).append("\n");
            }
        }

        sb.append("\n请根据以上需求，调用 search_pois 搜索景点，然后规划出真正定制化的旅行计划。\n");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // API 调用（天气）
    // ═══════════════════════════════════════════════════════════════

    private void collectApiData(String sessionId, AgentQuestionnairePO po, InstanceAnswers ans, SseEmitter emitter) {
        Map<String, Object> cache = readMap(po.getDataCache());
        callWeather(ans.getDestination(), cache, emitter);
        po.setDataCache(writeMap(cache));
    }

    private void callWeather(String city, Map<String, Object> cache, SseEmitter emitter) {
        if (city == null || city.isBlank()) return;
        sendEvent(emitter, "tool_call", Map.of("source", "weather", "action", "获取目的地天气"));
        try {
            WeatherTool.WeatherRequest wreq = new WeatherTool.WeatherRequest();
            wreq.setCity(city);
            wreq.setType("3d");
            WeatherTool.WeatherResponse wres = weatherTool.apply(wreq);
            Map<String, Object> weather = new LinkedHashMap<>();
            weather.put("city", city);
            weather.put("success", wres.isSuccess());
            if (wres.isSuccess() && wres.getWeatherList() != null && !wres.getWeatherList().isEmpty()) {
                List<Map<String, Object>> days = new ArrayList<>();
                for (WeatherTool.DailyWeather d : wres.getWeatherList()) {
                    Map<String, Object> day = new LinkedHashMap<>();
                    day.put("date", d.getDate());
                    day.put("text", d.getTextDay());
                    day.put("tempMax", d.getTempMax());
                    day.put("tempMin", d.getTempMin());
                    day.put("wind", d.getWindDay());
                    days.add(day);
                }
                weather.put("days", days);
            } else {
                weather.put("text", wres.getMessage());
            }
            cache.put("weather", weather);
            sendEvent(emitter, "tool_result", Map.of(
                    "source", "weather", "ok", wres.isSuccess(),
                    "summary", wres.isSuccess() ? "天气获取成功" : ("天气获取失败：" + wres.getMessage())));
        } catch (Exception e) {
            log.warn("天气调用失败: {}", e.getMessage());
            sendEvent(emitter, "tool_result", Map.of("source", "weather", "ok", false, "summary", "天气获取失败"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LLM 规范化
    // ═══════════════════════════════════════════════════════════════

    private Map<String, Object> normalizeStep(QuestionnaireStep step, String answer) {
        try {
            String sys = "你是旅行问卷参数解析器。用户会对一个特定问题给出口语化回答，\n请把它规范化成 JSON 对象输出，只输出 JSON，不要有任何解释或 markdown 代码块。";
            String user = "问题：「" + step.getQuestion() + "」\n字段：\"" + step.getField() + "\"\n用户回答：\"" + answer + "\"\n" +
                    "请输出 JSON，例如 destination → {\"destination\":\"杭州\"}；" +
                    "days → {\"days\":3}；budget → {\"budget\":3000}；" +
                    "madeAt → {\"madeAt\":\"轻松漫游\"}；" +
                    "interests → {\"interests\":\"美食,人文\"}(逗号分隔)。";

            ChatResponse resp = chatModel.call(new Prompt(List.of(
                    new SystemMessage(sys), new UserMessage(user))));
            String llmContent = resp.getResult().getOutput().getContent();
            log.info("LLM 规范化: field={}, output={}", step.getField(), llmContent);

            JsonNode node = extractJson(llmContent);
            if (node != null && node.isObject()) {
                Map<String, Object> raw = new HashMap<>();
                node.fields().forEachRemaining(e -> raw.put(e.getKey(), leafValue(e.getValue())));
                Object val = raw.get(step.getField());
                int depth = 0;
                while (val instanceof Map && depth < 5) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) val;
                    Object inner = nested.get(step.getField());
                    if (inner == null) break;
                    val = inner;
                    depth++;
                }
                Map<String, Object> result = new HashMap<>();
                result.put(step.getField(), val);
                return result;
            }
        } catch (Exception e) {
            log.warn("LLM 规范化失败，回退默认: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
        return fallbackParse(step, answer);
    }

    private Map<String, Object> fallbackParse(QuestionnaireStep step, String answer) {
        Map<String, Object> m = new HashMap<>();
        String a = answer == null ? "" : answer.trim();
        switch (step.getField()) {
            case "destination" -> m.put("destination", a.isEmpty() ? "杭州" : a);
            case "madeAt" -> m.put("madeAt", a.isEmpty() ? "轻松漫游" : a);
            case "days" -> {
                int d = 3;
                try { d = Integer.parseInt(a.replaceAll("\\D+", "")); } catch (Exception ignored) { }
                m.put("days", Math.max(1, Math.min(14, d == 0 ? 3 : d)));
            }
            case "interests" -> m.put("interests", a.isEmpty() ? "美食" : a);
            case "budget" -> {
                int b = 3000;
                try { b = Integer.parseInt(a.replaceAll("\\D+", "")); } catch (Exception ignored) { }
                m.put("budget", Math.max(100, b == 0 ? 3000 : b));
            }
            default -> m.put(step.getField(), a);
        }
        return m;
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════

    private Object leafValue(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isInt()) return n.asInt();
        if (n.isLong()) return n.asLong();
        if (n.isDouble()) return n.asDouble();
        if (n.isArray()) { List<Object> list = new ArrayList<>(); n.forEach(x -> list.add(leafValue(x))); return list; }
        if (n.isObject()) { Map<String, Object> obj = new HashMap<>(); n.fields().forEachRemaining(e -> obj.put(e.getKey(), leafValue(e.getValue()))); return obj; }
        return n.isNull() ? null : n.asText();
    }

    private JsonNode extractJson(String content) {
        if (content == null) return null;
        String c = content.trim();
        if (c.startsWith("```")) c = c.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        int start = c.indexOf('{');
        int end = c.lastIndexOf('}');
        if (start >= 0 && end > start) c = c.substring(start, end + 1);
        try { return objectMapper.readTree(c); } catch (Exception e) { return null; }
    }

    private InstanceAnswers instanceFromAnswers(Map<String, Object> answers) {
        return InstanceAnswers.builder()
                .destination(str(answers.get("destination")))
                .days(intVal(answers.get("days")))
                .interests(str(answers.get("interests")))
                .madeAt(str(answers.get("madeAt")))
                .budget(intVal(answers.get("budget")))
                .build();
    }

    private double[] distributeBudget(int totalBudget, int days) {
        days = Math.max(1, days);
        double[] result = new double[days];
        if (days == 1) { result[0] = totalBudget; return result; }
        double first = 0.28, rest = (1.0 - first) / (days - 1);
        double[] ratios = new double[days];
        for (int i = 0; i < days; i++) {
            if (i == 0) ratios[i] = first;
            else if (i == days - 1) ratios[i] = rest * 1.15;
            else ratios[i] = rest * (1.0 - (i - 1) * 0.05);
        }
        for (int i = 0; i < days; i++) result[i] = totalBudget * ratios[i];
        return result;
    }

    private boolean apiDone(String sessionId, int stepIndex) {
        Set<Integer> done = apiDone.get(sessionId);
        return done != null && done.contains(stepIndex);
    }

    private void markApiDone(String sessionId, int stepIndex) {
        apiDone.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(stepIndex);
    }

    private Map<String, Object> readMap(String json) {
        try {
            if (json == null || json.isBlank()) return new HashMap<>();
            JsonNode n = objectMapper.readTree(json);
            Map<String, Object> m = new HashMap<>();
            if (n.isObject()) n.fields().forEachRemaining(e -> m.put(e.getKey(), leafValue(e.getValue())));
            return m;
        } catch (Exception e) { return new HashMap<>(); }
    }

    private String writeMap(Map<String, Object> map) {
        try { return objectMapper.writeValueAsString(map); } catch (Exception e) { return "{}"; }
    }

    private String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private String nvl(String s, String def) { return (s != null && !s.isBlank()) ? s : def; }

    private int intVal(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }

    private ArrayNode listToArray(List<String> list) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (String s : list) arr.add(s);
        return arr;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try { emitter.send(SseEmitter.event().name(name).data(objectMapper.writeValueAsString(data))); }
        catch (IOException e) { log.warn("SSE 发送失败: event={}", name, e); }
    }

    // ═══════════════════════════════════════════════════════════════
    // 健康检查
    // ═══════════════════════════════════════════════════════════════

    public Map<String, Object> healthCheck() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chatModelClass", chatModel != null ? chatModel.getClass().getSimpleName() : "null");
        result.put("chatModelNotNull", chatModel != null);
        try {
            long t0 = System.currentTimeMillis();
            ChatResponse resp = chatModel.call(new Prompt(List.of(
                    new SystemMessage("You are a helpful assistant."),
                    new UserMessage("Reply with just 'OK'"))));
            long t1 = System.currentTimeMillis();
            String output = resp.getResult().getOutput().getContent();
            result.put("llmCall", "success");
            result.put("latencyMs", t1 - t0);
            result.put("output", output);
            result.put("modelUsed", resp.getMetadata() != null ? resp.getMetadata().getModel() : "unknown");
            if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                var usage = resp.getMetadata().getUsage();
                Map<String, Object> usageMap = new LinkedHashMap<>();
                usageMap.put("promptTokens", usage.getPromptTokens());
                usageMap.put("generationTokens", usage.getGenerationTokens());
                usageMap.put("totalTokens", usage.getTotalTokens());
                result.put("usage", usageMap);
            } else {
                result.put("usage", "null");
            }
        } catch (Exception e) {
            result.put("llmCall", "failed");
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部类
    // ═══════════════════════════════════════════════════════════════

    private static class QuestionnaireStep {
        int index;
        String field;
        String type;
        List<String> options;
        boolean llmParse;
        String apiTrigger;
        String question;

        public int getIndex() { return index; }
        public String getField() { return field; }
        public String getType() { return type; }
        public List<String> getOptions() { return options; }
        public String getApiTrigger() { return apiTrigger; }
        public String getQuestion() { return question; }

        public static QuestionnaireStepBuilder builder() { return new QuestionnaireStepBuilder(); }

        private static class QuestionnaireStepBuilder {
            private final QuestionnaireStep step = new QuestionnaireStep();
            public QuestionnaireStepBuilder index(int i) { step.index = i; return this; }
            public QuestionnaireStepBuilder field(String f) { step.field = f; return this; }
            public QuestionnaireStepBuilder type(String t) { step.type = t; return this; }
            public QuestionnaireStepBuilder options(List<String> o) { step.options = o; return this; }
            public QuestionnaireStepBuilder llmParse(boolean b) { step.llmParse = b; return this; }
            public QuestionnaireStepBuilder apiTrigger(String t) { step.apiTrigger = t; return this; }
            public QuestionnaireStepBuilder question(String q) { step.question = q; return this; }
            public QuestionnaireStep build() { return step; }
        }
    }

    @lombok.Builder
    @lombok.Getter
    private static class InstanceAnswers {
        private String destination;
        private int days;
        private String interests;
        private String madeAt;
        private int budget;
    }
}
