package com.travel.a2a.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.a2a.model.*;
import com.travel.a2a.service.orchestrator.ItineraryOrchestrator;
import com.travel.mcp.protocol.a2a.A2AStreamEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 主Agent服务（协调者）
 * 
 * <p>负责协调子Agent执行、收集结果、LLM优化，并通过SSE流输出各阶段事件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HostAgentService {

    private final ItineraryOrchestrator orchestrator;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final ItineraryPlanValidator planValidator;

    /**
     * 执行行程规划并通过SSE流输出
     *
     * @param request 行程请求
     * @param taskId  任务ID
     * @return SseEmitter
     */
    @Async("taskExecutor")
    public void plan(TravelPlanRequest request, String taskId, SseEmitter emitter) {
        // 设置完成和超时回调
        emitter.onCompletion(() -> log.info("SSE流完成: taskId={}", taskId));
        emitter.onTimeout(() -> log.warn("SSE流超时: taskId={}", taskId));
        emitter.onError(e -> log.error("SSE流异常: taskId={}", taskId, e));

        try {
            executePlan(request, taskId, emitter);
        } catch (Exception e) {
            log.error("执行行程规划异常: taskId={}", taskId, e);
            sendError(emitter, e.getMessage());
        } finally {
            emitter.complete();
        }
    }

    /**
     * 执行行程规划
     */
    private void executePlan(TravelPlanRequest request, String taskId, SseEmitter emitter) {
        try {
            // 1. 发送任务开始事件
            sendEvent(emitter, "task_update", A2AStreamEvent.taskUpdate(
                    java.util.Map.of("taskId", taskId, "status", "started",
                            "message", "开始规划行程...")));

            // 2. 发送工具调用事件（并行调用前）
            sendEvent(emitter, "tool_call", A2AStreamEvent.toolCall(
                    java.util.Map.of("source", "weather", "action", "获取天气信息")));

            sendEvent(emitter, "tool_call", A2AStreamEvent.toolCall(
                    java.util.Map.of("source", "poi", "action", "搜索景点")));

            sendEvent(emitter, "tool_call", A2AStreamEvent.toolCall(
                    java.util.Map.of("source", "meal", "action", "搜索餐厅")));

            sendEvent(emitter, "tool_call", A2AStreamEvent.toolCall(
                    java.util.Map.of("source", "budget", "action", "估算预算")));

            // 3. 并行执行子Agent编排
            TravelPlanResult result = orchestrator.orchestrate(request);

            // 4. 发送工具结果事件
            if (result.getDataWarnings() != null) {
                for (DataWarning warning : result.getDataWarnings()) {
                    sendEvent(emitter, "tool_result", A2AStreamEvent.toolResult(
                            java.util.Map.of("source", warning.getSource(),
                                    "type", "warning",
                                    "message", warning.getMessage(),
                                    "elapsedMs", warning.getElapsedMs())));
                }
            }

            // 发送成功的结果
            if (result.getWeather() != null && result.getWeather().isSuccess()) {
                sendEvent(emitter, "tool_result", A2AStreamEvent.toolResult(
                        java.util.Map.of("source", "weather", "type", "success")));
            }
            if (result.getPois() != null && !result.getPois().isEmpty()) {
                sendEvent(emitter, "tool_result", A2AStreamEvent.toolResult(
                        java.util.Map.of("source", "poi", "type", "success",
                                "count", result.getPois().size())));
            }
            if (result.getMeals() != null && !result.getMeals().isEmpty()) {
                sendEvent(emitter, "tool_result", A2AStreamEvent.toolResult(
                        java.util.Map.of("source", "meal", "type", "success",
                                "count", result.getMeals().size())));
            }
            if (result.getBudget() != null && result.getBudget().isSuccess()) {
                sendEvent(emitter, "tool_result", A2AStreamEvent.toolResult(
                        java.util.Map.of("source", "budget", "type", "success")));
            }

            // 5. 发送LLM优化中的token
            sendEvent(emitter, "task_update", A2AStreamEvent.taskUpdate(
                    java.util.Map.of("taskId", taskId, "status", "optimizing",
                            "message", "LLM优化行程中...")));

            // 6. 使用LLM优化行程
            String finalPlan = optimizeWithLLM(result, request);
            if (finalPlan == null || finalPlan.isBlank()) {
                log.warn("LLM结构化计划不可用，使用确定性行程兜底");
                finalPlan = buildDeterministicPlan(result);
            }

            // 分段发送最终行程
            if (finalPlan != null && !finalPlan.isEmpty()) {
                sendEvent(emitter, "token", A2AStreamEvent.token(finalPlan));
            } else {
                // 如果LLM优化失败，发送原始行程数据
                String resultJson = objectMapper.writeValueAsString(result);
                sendEvent(emitter, "token", A2AStreamEvent.token(resultJson));
            }

            // 7. 发送完成事件
            result.setFinalPlan(finalPlan);
            sendEvent(emitter, "task_done", A2AStreamEvent.taskDone(result));

            log.info("行程规划完成: taskId={}", taskId);

        } catch (Exception e) {
            log.error("执行行程规划失败: taskId={}", taskId, e);
            sendError(emitter, e.getMessage());
        }
    }

    /**
     * 使用 LLM 生成结构化候选，并由 Java 做一次受限修复和确定性校验。
     *
     * <p>模型输出不直接作为最终 Markdown，也不直接决定价格。第一次校验失败后
     * 只允许再生成一次；仍失败就进入确定性模板。</p>
     */
    private String optimizeWithLLM(TravelPlanResult result, TravelPlanRequest request) {
        log.info("HostAgentService: 开始结构化LLM优化");
        List<String> repairErrors = List.of();
        LlmItineraryPlan lastCandidate = null;

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                LlmItineraryPlan candidate = callStructuredPlan(result, request, repairErrors);
                lastCandidate = candidate;
                ItineraryPlanValidator.ValidationResult validation = planValidator.validate(
                        candidate, request, result);
                if (validation.valid()) {
                    log.info("HostAgentService: 结构化计划校验通过, attempt={}, verifiedCost={}",
                            attempt, validation.verifiedTotal());
                    return renderVerifiedPlan(candidate, validation, result, request);
                }

                repairErrors = validation.errors();
                log.warn("HostAgentService: 结构化计划校验失败, attempt={}, errors={}",
                        attempt, String.join("; ", repairErrors));
            } catch (Exception e) {
                // 网络/供应商超时不是结构化校验失败；重发只会放大延迟和供应商压力。
                if (isTransportFailure(e)) {
                    log.warn("HostAgentService: LLM请求超时或暂时不可用，跳过修复重试");
                    break;
                }

                // 不把供应商原始异常或模型原文回传给用户/模型，只给下一次修复一个稳定提示。
                repairErrors = List.of("输出不是符合协议的 JSON，或无法反序列化为行程 DTO");
                log.warn("HostAgentService: 结构化LLM输出不可用, attempt={}, causeType={}",
                        attempt, e.getClass().getSimpleName());
            }
        }

        if (lastCandidate != null) {
            String partialPlan = renderPartiallyVerifiedPlan(lastCandidate, result, request);
            if (partialPlan != null) return partialPlan;
        }
        return null;
    }

    private boolean isTransportFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ResourceAccessException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private LlmItineraryPlan callStructuredPlan(TravelPlanResult result,
                                                TravelPlanRequest request,
                                                List<String> repairErrors) throws Exception {
        ChatResponse response = chatModel.call(buildStructuredPrompt(result, request, repairErrors));
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getContent() == null
                || response.getResult().getOutput().getContent().isBlank()) {
            throw new IllegalStateException("模型未返回内容");
        }

        String json = extractJsonObject(response.getResult().getOutput().getContent());
        return objectMapper.readValue(json, LlmItineraryPlan.class);
    }

    private Prompt buildStructuredPrompt(TravelPlanResult result,
                                         TravelPlanRequest request,
                                         List<String> repairErrors) throws JsonProcessingException {
        String systemPrompt =
                "你是 Roamly 的行程候选生成器。只输出一个 JSON 对象，不要输出 Markdown、解释文字或代码块。\n" +
                "只能使用用户请求和候选工具结果中的地点；placeId 必须逐字复制候选集中的值，禁止创造地点、价格、天气、开放状态或维护原因。\n" +
                "schemaVersion 固定为 1。days 必须覆盖 1 到 N 且每个 dayNo 只出现一次。\n" +
                "每个活动必须包含 type、startTime、endTime；景点和餐厅必须包含候选 placeId。\n" +
                "estimatedCost、dailyCost、totalCost 只是候选值，服务端会根据工具结果重算；无法核验时使用 null。\n" +
                "可用类型只有 ATTRACTION、RESTAURANT、TRANSPORT；交通没有候选地点，placeId 必须为 null。\n" +
                "禁止时间重叠、禁止重复地点、禁止输出异常堆栈。";

        StringBuilder userMessage = new StringBuilder();
        userMessage.append("用户请求：\n")
                .append("目的地：").append(request.getDestination()).append("\n")
                .append("天数：").append(request.getDays()).append("\n")
                .append("人数：").append(request.getTravelers()).append("\n")
                .append("预算上限：").append(request.getBudget()).append("\n")
                .append("旅行风格：").append(request.getTravelStyle()).append("\n\n");

        if (result.getDataWarnings() != null && !result.getDataWarnings().isEmpty()) {
            userMessage.append("数据告警（不可用于补全事实）：\n");
            for (DataWarning warning : result.getDataWarnings()) {
                userMessage.append("- ").append(warning.getSource()).append("：")
                        .append(warning.getMessage()).append("\n");
            }
            userMessage.append("\n");
        }

        String sampleDate = result.getDayPlans() == null || result.getDayPlans().isEmpty()
                ? "2026-01-01" : result.getDayPlans().get(0).getDate();
        if (sampleDate == null || sampleDate.isBlank()) sampleDate = "2026-01-01";
        userMessage.append("候选地点（只能从这里选择）：\n")
                .append(objectMapper.writeValueAsString(candidatePayload(result))).append("\n\n")
                .append("服务端期望日期：\n")
                .append(objectMapper.writeValueAsString(expectedDates(result))).append("\n\n")
                .append("返回格式示例（仅示意字段，不要照抄不存在的 placeId）：\n")
                .append("{\"schemaVersion\":1,\"days\":[{\"dayNo\":1,\"date\":\"")
                .append(sampleDate)
                .append("\",\"items\":[{\"type\":\"ATTRACTION\",\"placeId\":\"poi:example\",\"startTime\":\"09:00\",\"endTime\":\"11:00\",\"estimatedCost\":null,\"note\":null}],\"dailyCost\":null}],\"totalCost\":null}\n\n");

        if (repairErrors != null && !repairErrors.isEmpty()) {
            userMessage.append("上一次输出未通过服务端校验，请只修复以下问题后重新生成完整 JSON：\n");
            for (String error : repairErrors) {
                userMessage.append("- ").append(error).append("\n");
            }
        }

        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userMessage.toString()));

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withResponseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null))
                .withTemperature(0.2d)
                .withMaxTokens(4096)
                .build();
        return new Prompt(messages, options);
    }

    private List<Map<String, Object>> candidatePayload(TravelPlanResult result) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ItineraryPlanValidator.CandidatePlace candidate : planValidator.indexCandidates(result).values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("placeId", candidate.placeId());
            row.put("type", candidate.type().name());
            row.put("name", candidate.name());
            if (candidate.address() != null) row.put("address", candidate.address());
            if (candidate.unitCost() != null) row.put("avgPricePerPerson", candidate.unitCost());
            payload.add(row);
        }
        return payload;
    }

    private List<Map<String, Object>> expectedDates(TravelPlanResult result) {
        List<Map<String, Object>> dates = new ArrayList<>();
        if (result.getDayPlans() == null) return dates;
        for (DayPlan day : result.getDayPlans()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dayNo", day.getDay());
            row.put("date", day.getDate());
            dates.add(row);
        }
        return dates;
    }

    private String extractJsonObject(String content) {
        String normalized = content.trim();
        if (normalized.startsWith("```")) {
            int firstLineEnd = normalized.indexOf('\n');
            if (firstLineEnd >= 0) normalized = normalized.substring(firstLineEnd + 1);
            if (normalized.endsWith("```")) normalized = normalized.substring(0, normalized.length() - 3).trim();
        }
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("未找到 JSON 对象");
        return normalized.substring(start, end + 1);
    }

    private String renderPartiallyVerifiedPlan(LlmItineraryPlan candidate,
                                               TravelPlanResult result,
                                               TravelPlanRequest request) {
        LlmItineraryPlan safeCandidate = planValidator.retainSafeFragments(candidate, request, result);
        if (safeCandidate == null) return null;

        ItineraryPlanValidator.ValidationResult validation = planValidator.validate(
                safeCandidate, request, result);
        if (!validation.valid()) {
            // 没有任何可保留的候选地点时，直接使用完整确定性模板，避免输出空壳计划。
            log.info("HostAgentService: 无可保留的结构化片段，使用确定性模板");
            return null;
        }
        return renderPlan(safeCandidate, validation.candidates(), validation.verifiedTotal(),
                validation.hasUnknownCosts(), result, request, true);
    }

    private String renderVerifiedPlan(LlmItineraryPlan candidate,
                                      ItineraryPlanValidator.ValidationResult validation,
                                      TravelPlanResult result,
                                      TravelPlanRequest request) {
        return renderPlan(candidate, validation.candidates(), validation.verifiedTotal(),
                validation.hasUnknownCosts(), result, request, false);
    }

    private String renderPlan(LlmItineraryPlan candidate,
                              Map<String, ItineraryPlanValidator.CandidatePlace> candidates,
                              BigDecimal verifiedTotal,
                              boolean hasUnknownCosts,
                              TravelPlanResult result,
                              TravelPlanRequest request,
                              boolean fillEmptyDaysWithDeterministicTemplate) {
        StringBuilder out = new StringBuilder("# ✨ Roamly 私人旅行方案\n\n");
        if (fillEmptyDaysWithDeterministicTemplate) {
            out.append("模型输出未完全通过整体校验；以下仅保留通过确定性校验的活动，缺失部分使用工具数据驱动的模板补齐。\n\n");
        }
        out.append(result.getDestination()).append(" · ").append(request.getDays()).append("日可执行行程\n\n");
        out.append("## 费用核验\n")
                .append("已核验费用下限：¥").append(money(verifiedTotal))
                .append("（餐饮按工具返回的人均价格 × 人数计算）\n");
        if (hasUnknownCosts) {
            out.append("门票、交通或缺少价格的项目未计入，相关费用显示为“暂无数据”，不能据此认为实际总费用已确定。\n");
        }
        out.append("\n");

        Map<Integer, DayPlan> serviceDays = new LinkedHashMap<>();
        if (result.getDayPlans() != null) {
            for (DayPlan day : result.getDayPlans()) serviceDays.put(day.getDay(), day);
        }

        List<LlmItineraryPlan.Day> days = new ArrayList<>(candidate.getDays());
        days.sort(Comparator.comparing(LlmItineraryPlan.Day::getDayNo));
        for (LlmItineraryPlan.Day day : days) {
            DayPlan serviceDay = serviceDays.get(day.getDayNo());
            out.append("## 第").append(day.getDayNo()).append("天");
            if (serviceDay != null && serviceDay.getTheme() != null) out.append(" · ").append(serviceDay.getTheme());
            out.append("\n");
            out.append("日期：").append(day.getDate()).append("  |  天气：")
                    .append(serviceDay == null || serviceDay.getWeather() == null
                            ? "暂无天气数据" : serviceDay.getWeather())
                    .append("  |  今日预算：¥")
                    .append(serviceDay == null ? "暂无数据" : Math.round(serviceDay.getDailyBudget()))
                    .append("\n\n");

            List<LlmItineraryPlan.Item> items = day.getItems() == null
                    ? List.of() : new ArrayList<>(day.getItems());
            items.sort(Comparator.comparing(LlmItineraryPlan.Item::getStartTime));
            if (items.isEmpty()) {
                if (fillEmptyDaysWithDeterministicTemplate && serviceDay != null
                        && serviceDay.getActivities() != null && !serviceDay.getActivities().isEmpty()) {
                    out.append("- 本日没有保留到通过校验的模型活动，以下为可信工具数据驱动的确定性安排：\n\n");
                    appendDeterministicActivities(out, serviceDay);
                } else {
                    out.append("- 暂无可靠候选活动，未使用虚构数据补齐。\n\n");
                }
            }
            BigDecimal dailyVerified = BigDecimal.ZERO;
            for (LlmItineraryPlan.Item item : items) {
                ItineraryPlanValidator.CandidatePlace place = candidates.get(item.getPlaceId());
                String name = place == null ? "交通安排" : place.name();
                String address = place == null || place.address() == null ? "暂无地点数据" : place.address();
                BigDecimal unitCost = place == null ? null : place.unitCost();
                if (unitCost != null) {
                    dailyVerified = dailyVerified.add(unitCost
                            .multiply(BigDecimal.valueOf(Math.max(1, request.getTravelers()))));
                }

                out.append("### ").append(item.getStartTime()).append("-")
                        .append(item.getEndTime()).append(" · ").append(name).append("\n")
                        .append("- 地点：").append(address).append("\n")
                        .append("- 类型：").append(item.getType()).append("\n")
                        .append("- 已核验费用：").append(unitCost == null ? "暂无数据" : "¥" + money(unitCost))
                        .append(item.getType() == LlmItineraryPlan.ItemType.RESTAURANT ? "（人均）" : "")
                        .append("\n- 说明：").append(renderNote(item.getType())).append("\n\n");
            }
            out.append("当日已核验费用下限：¥").append(money(dailyVerified)).append("\n\n");
        }
        out.append("## 出行提醒\n- 出发前确认开放时间、预约和实时天气。\n- 未返回的门票、交通和价格信息请在出发前自行确认。\n");
        return out.toString();
    }

    private void appendDeterministicActivities(StringBuilder out, DayPlan day) {
        for (DayPlan.Activity activity : day.getActivities()) {
            out.append("### ").append(activity.getTime()).append(" · ").append(activity.getName()).append("\n")
                    .append("- 地点：").append(activity.getLocation()).append("\n")
                    .append("- 交通：").append(activity.getTransport()).append("\n")
                    .append("- 停留：").append(activity.getDuration()).append(" 分钟\n")
                    .append("- 费用：").append(activity.getCost() == null
                            ? "暂无数据" : "¥" + Math.round(activity.getCost())).append("\n")
                    .append("- 说明：").append(activity.getNotes()).append("\n\n");
        }
    }

    private String renderNote(LlmItineraryPlan.ItemType type) {
        return switch (type) {
            case ATTRACTION -> "地点来自景点候选工具；门票费用暂无可靠数据。";
            case RESTAURANT -> "地点和人均价格来自餐饮候选工具；实际消费请以现场为准。";
            case TRANSPORT -> "交通费用和具体方式暂无可靠数据。";
        };
    }

    private String money(BigDecimal value) {
        if (value == null) return "暂无数据";
        return value.stripTrailingZeros().toPlainString();
    }

    /** 不依赖 LLM 的完整兜底，确保 API 永远返回可执行的每日计划。 */
    private String buildDeterministicPlan(TravelPlanResult result) {
        StringBuilder out = new StringBuilder("# ✨ Roamly 私人旅行方案\n\n");
        String city = result.getDestination() == null ? (result.getWeather() == null ? "目的地" : result.getWeather().getCity()) : result.getDestination();
        out.append(city).append(" · " ).append(result.getDayPlans() == null ? 0 : result.getDayPlans().size()).append("日可执行行程\n\n");
        if (result.getStrategyNotes() != null) { out.append("## AI 旅行策略\n"); for (String n : result.getStrategyNotes()) out.append("- ").append(n).append("\n"); out.append("\n"); }
        if (result.getDataWarnings() != null && !result.getDataWarnings().isEmpty()) {
            out.append("## 数据可用性说明\n");
            for (DataWarning warning : result.getDataWarnings()) {
                out.append("- ").append(warning.getSource()).append("：")
                        .append(warning.getMessage())
                        .append("；缺失信息未使用虚构数据补全。\n");
            }
            out.append("\n");
        }
        if (result.getDayPlans() != null) for (DayPlan day : result.getDayPlans()) {
            out.append("## 第").append(day.getDay()).append("天 · ").append(day.getTheme() == null ? "城市探索" : day.getTheme()).append("\n");
            out.append("日期：").append(day.getDate()).append("  |  天气：")
                    .append(day.getWeather() == null ? "暂无天气数据" : day.getWeather()).append(" ")
                    .append(day.getTemperature() == null ? "" : day.getTemperature())
                    .append("  |  今日预算：¥").append(Math.round(day.getDailyBudget())).append("\n\n");
            if (day.getActivities() != null) for (DayPlan.Activity a : day.getActivities()) {
                out.append("### " ).append(a.getTime()).append(" · " ).append(a.getName()).append("\n");
                out.append("- 地点：").append(a.getLocation()).append("\n- 交通：").append(a.getTransport()).append("\n- 停留：").append(a.getDuration()).append(" 分钟\n- 预计费用：")
                        .append(a.getCost() == null ? "暂无数据" : "¥" + Math.round(a.getCost()))
                        .append("\n- 推荐理由：").append(a.getNotes()).append("\n\n");
            }
            out.append("**今日执行建议：** 按时间顺序出发，地点之间优先使用公共交通或步行；如遇天气变化，优先替换为室内活动。\n\n");
        }
        out.append("## 出行提醒\n- 出发前确认开放时间、预约和天气。\n- 每天保留机动时间，不建议跨区域折返。\n");
        return out.toString();
    }

    /**
     * 发送SSE事件
     */
    private void sendEvent(SseEmitter emitter, String eventName, A2AStreamEvent event) {
        try {
            String data = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } catch (IOException | IllegalStateException e) {
            log.warn("发送SSE事件失败: eventName={}", eventName, e);
        }
    }

    /**
     * 发送错误事件
     */
    private void sendError(SseEmitter emitter, String errorMessage) {
        try {
            sendEvent(emitter, "error", A2AStreamEvent.error(
                    java.util.Map.of("message", errorMessage == null || errorMessage.isBlank()
                            ? "服务暂时不可用" : errorMessage)));
        } catch (Exception e) {
            log.warn("发送错误事件失败", e);
        }
    }
}
