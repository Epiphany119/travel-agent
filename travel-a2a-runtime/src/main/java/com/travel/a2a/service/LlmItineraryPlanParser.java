package com.travel.a2a.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.a2a.model.LlmItineraryPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/**
 * LLM 行程 JSON 的唯一解析入口。
 *
 * <p>模型输出是不可信输入。这里先做 JSON 结构和字段白名单检查，再交给 Jackson
 * 反序列化，避免未知字段（例如 action、购买套餐参数）被静默忽略或进入后续流程。</p>
 */
@Component
@RequiredArgsConstructor
public class LlmItineraryPlanParser {

    private static final Set<String> PLAN_FIELDS = Set.of("schemaVersion", "days", "totalCost");
    private static final Set<String> DAY_FIELDS = Set.of("dayNo", "date", "items", "dailyCost");
    private static final Set<String> ITEM_FIELDS = Set.of(
            "type", "placeId", "startTime", "endTime", "estimatedCost", "note");

    private final ObjectMapper objectMapper;

    /**
     * 解析模型返回的结构化行程。
     *
     * @param content 模型原始文本，可带标准 Markdown JSON 代码围栏
     * @return 尚未通过事实/预算校验的 LLM 候选
     * @throws IOException JSON 无法解析时抛出
     */
    public LlmItineraryPlan parse(String content) throws IOException {
        String json = extractJsonObject(content);
        JsonNode root;
        try (JsonParser parser = objectMapper.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("JSON 对象后存在额外内容");
            }
        }

        requireObject(root, "$", false);
        validatePlanShape(root);
        return objectMapper.treeToValue(root, LlmItineraryPlan.class);
    }

    private void validatePlanShape(JsonNode root) {
        rejectUnknownFields(root, PLAN_FIELDS, "$", true);
        requireField(root, "schemaVersion", "$");
        requireField(root, "days", "$");
        requireIntegralOrNull(root.get("schemaVersion"), "$.schemaVersion", false);
        requireArray(root.get("days"), "$.days", false);
        requireNumberOrNull(root.get("totalCost"), "$.totalCost");

        for (int i = 0; i < root.get("days").size(); i++) {
            JsonNode day = root.get("days").get(i);
            String path = "$.days[" + i + "]";
            requireObject(day, path, false);
            rejectUnknownFields(day, DAY_FIELDS, path, true);
            requireField(day, "dayNo", path);
            requireField(day, "date", path);
            requireField(day, "items", path);
            requireIntegralOrNull(day.get("dayNo"), path + ".dayNo", false);
            requireTextOrNull(day.get("date"), path + ".date", false);
            requireArray(day.get("items"), path + ".items", false);
            requireNumberOrNull(day.get("dailyCost"), path + ".dailyCost");

            for (int j = 0; j < day.get("items").size(); j++) {
                JsonNode item = day.get("items").get(j);
                String itemPath = path + ".items[" + j + "]";
                requireObject(item, itemPath, false);
                rejectUnknownFields(item, ITEM_FIELDS, itemPath, true);
                requireField(item, "type", itemPath);
                requireField(item, "placeId", itemPath);
                requireField(item, "startTime", itemPath);
                requireField(item, "endTime", itemPath);
                requireField(item, "estimatedCost", itemPath);
                requireTextOrNull(item.get("type"), itemPath + ".type", false);
                requireTextOrNull(item.get("placeId"), itemPath + ".placeId", true);
                requireTextOrNull(item.get("startTime"), itemPath + ".startTime", false);
                requireTextOrNull(item.get("endTime"), itemPath + ".endTime", false);
                requireNumberOrNull(item.get("estimatedCost"), itemPath + ".estimatedCost");
                requireTextOrNull(item.get("note"), itemPath + ".note", true);
            }
        }
    }

    private String extractJsonObject(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("模型未返回 JSON");
        }

        String normalized = content.trim();
        if (normalized.startsWith("```")) {
            int firstLineEnd = normalized.indexOf('\n');
            if (firstLineEnd < 0 || !normalized.endsWith("```")) {
                throw new IllegalArgumentException("Markdown JSON 代码围栏不完整");
            }
            String language = normalized.substring(3, firstLineEnd).trim();
            if (!language.isEmpty() && !"json".equalsIgnoreCase(language)) {
                throw new IllegalArgumentException("只允许 json Markdown 代码围栏");
            }
            normalized = normalized.substring(firstLineEnd + 1, normalized.length() - 3).trim();
        }
        if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
            throw new IllegalArgumentException("模型输出必须是单个 JSON 对象");
        }
        return normalized;
    }

    private void rejectUnknownFields(JsonNode object,
                                     Set<String> allowed,
                                     String path,
                                     boolean rejectUnknown) {
        if (!rejectUnknown) return;
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException(path + " 包含未允许字段: " + name);
            }
        }
    }

    private void requireField(JsonNode object, String field, String path) {
        if (!object.has(field)) {
            throw new IllegalArgumentException(path + " 缺少字段: " + field);
        }
    }

    private void requireObject(JsonNode node, String path, boolean allowNull) {
        if (node == null) {
            if (allowNull) return;
            throw new IllegalArgumentException(path + " 必须是 JSON 对象");
        }
        if (node.isNull() && allowNull) return;
        if (node.isNull() || !node.isObject()) {
            throw new IllegalArgumentException(path + " 必须是 JSON 对象");
        }
    }

    private void requireArray(JsonNode node, String path, boolean allowNull) {
        if (node == null) {
            if (allowNull) return;
            throw new IllegalArgumentException(path + " 必须是 JSON 数组");
        }
        if (node.isNull() && allowNull) return;
        if (node.isNull() || !node.isArray()) {
            throw new IllegalArgumentException(path + " 必须是 JSON 数组");
        }
    }

    private void requireIntegralOrNull(JsonNode node, String path, boolean allowNull) {
        if (node == null) {
            if (allowNull) return;
            throw new IllegalArgumentException(path + " 必须是整数");
        }
        if (node.isNull() && allowNull) return;
        if (node.isNull() || !node.isIntegralNumber()) {
            throw new IllegalArgumentException(path + " 必须是整数");
        }
    }

    private void requireNumberOrNull(JsonNode node, String path) {
        if (node == null || node.isNull()) return;
        if (!node.isNumber()) {
            throw new IllegalArgumentException(path + " 必须是数字或 null");
        }
    }

    private void requireTextOrNull(JsonNode node, String path, boolean allowNull) {
        if (node == null) {
            if (allowNull) return;
            throw new IllegalArgumentException(path + " 必须是字符串");
        }
        if (node.isNull() && allowNull) return;
        if (node.isNull() || !node.isTextual()) {
            throw new IllegalArgumentException(path + " 必须是字符串" + (allowNull ? "或 null" : ""));
        }
    }
}
