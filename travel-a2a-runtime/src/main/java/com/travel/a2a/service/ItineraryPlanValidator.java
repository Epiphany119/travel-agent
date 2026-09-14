package com.travel.a2a.service;

import com.travel.a2a.model.DayPlan;
import com.travel.a2a.model.LlmItineraryPlan;
import com.travel.a2a.model.MealResult;
import com.travel.a2a.model.PoiResult;
import com.travel.a2a.model.TravelPlanRequest;
import com.travel.a2a.model.TravelPlanResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.text.Normalizer;

/**
 * 对 LLM 结构化行程做确定性校验。
 *
 * <p>LLM 只能提出候选方案，不能决定地点真实性、时间冲突或最终费用。这里所有
 * 约束都基于用户请求和工具返回的数据判断，费用也会重新计算。</p>
 */
@Component
public class ItineraryPlanValidator {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_ERRORS = 20;
    private static final int MAX_EXTERNAL_TEXT_LENGTH = 160;

    public ValidationResult validate(LlmItineraryPlan plan,
                                     TravelPlanRequest request,
                                     TravelPlanResult toolResult) {
        List<String> errors = new ArrayList<>();
        Map<String, CandidatePlace> candidates = indexCandidates(toolResult);
        BigDecimal verifiedTotal = BigDecimal.ZERO;
        boolean hasUnknownCosts = false;

        if (plan == null) {
            addError(errors, "模型未返回行程对象");
            return invalid(errors, candidates, verifiedTotal, true);
        }
        if (!Integer.valueOf(SCHEMA_VERSION).equals(plan.getSchemaVersion())) {
            addError(errors, "schemaVersion 必须为 " + SCHEMA_VERSION);
        }

        int expectedDays = request == null ? 0 : request.getDays();
        List<LlmItineraryPlan.Day> days = plan.getDays();
        if (days == null) {
            addError(errors, "缺少 days");
            return invalid(errors, candidates, verifiedTotal, true);
        }
        if (days.size() != expectedDays) {
            addError(errors, "行程天数应为 " + expectedDays + " 天，实际为 " + days.size() + " 天");
        }

        Set<Integer> dayNos = new HashSet<>();
        Set<String> usedPlaceIds = new HashSet<>();
        int travelers = request == null ? 1 : Math.max(1, request.getTravelers());
        boolean selectedCandidate = false;

        for (LlmItineraryPlan.Day day : days) {
            if (day == null) {
                addError(errors, "days 中包含空对象");
                continue;
            }

            Integer dayNo = day.getDayNo();
            if (dayNo == null || dayNo < 1 || dayNo > expectedDays) {
                addError(errors, "dayNo 必须在 1 到 " + expectedDays + " 之间");
            } else if (!dayNos.add(dayNo)) {
                addError(errors, "dayNo=" + dayNo + " 重复");
            }

            if (day.getDate() == null) {
                addError(errors, "第 " + displayDay(dayNo) + " 天缺少 date");
            } else {
                validateDate(day, dayNo, toolResult, errors);
            }

            if (day.getDailyCost() != null && day.getDailyCost().signum() < 0) {
                addError(errors, "第 " + displayDay(dayNo) + " 天 dailyCost 不能为负数");
            }

            List<LlmItineraryPlan.Item> items = day.getItems();
            if (items == null) {
                addError(errors, "第 " + displayDay(dayNo) + " 天缺少 items");
                continue;
            }

            List<TimedItem> timedItems = new ArrayList<>();
            for (LlmItineraryPlan.Item item : items) {
                if (item == null) {
                    addError(errors, "第 " + displayDay(dayNo) + " 天包含空活动");
                    continue;
                }
                validateCostShape(item, errors);
                if (item.getStartTime() == null || item.getEndTime() == null) {
                    addError(errors, "第 " + displayDay(dayNo) + " 天活动缺少 startTime 或 endTime");
                } else if (!item.getStartTime().isBefore(item.getEndTime())) {
                    addError(errors, "第 " + displayDay(dayNo) + " 天存在无效时间区间");
                } else {
                    timedItems.add(new TimedItem(item.getStartTime(), item.getEndTime()));
                }

                LlmItineraryPlan.ItemType type = item.getType();
                if (type == null) {
                    addError(errors, "第 " + displayDay(dayNo) + " 天活动缺少 type");
                    continue;
                }

                if (type == LlmItineraryPlan.ItemType.TRANSPORT) {
                    if (hasText(item.getPlaceId())) {
                        addError(errors, "TRANSPORT 不允许关联候选地点");
                    }
                    // 没有交通工具结果来源，模型费用不能进入已核验预算。
                    hasUnknownCosts = true;
                    continue;
                }

                if (!hasText(item.getPlaceId())) {
                    addError(errors, type + " 活动缺少 placeId");
                    hasUnknownCosts = true;
                    continue;
                }

                CandidatePlace candidate = candidates.get(item.getPlaceId());
                if (candidate == null) {
                    addError(errors, "地点不在工具候选集中: " + item.getPlaceId());
                    hasUnknownCosts = true;
                    continue;
                }
                if (candidate.type() != type) {
                    addError(errors, "地点类型与候选来源不一致: " + item.getPlaceId());
                } else {
                    selectedCandidate = true;
                }
                if (!usedPlaceIds.add(item.getPlaceId())) {
                    addError(errors, "地点重复安排: " + item.getPlaceId());
                }

                if (candidate.unitCost() == null) {
                    hasUnknownCosts = true;
                } else {
                    verifiedTotal = verifiedTotal.add(candidate.unitCost()
                            .multiply(BigDecimal.valueOf(travelers)));
                }
            }

            timedItems.sort(Comparator.comparing(TimedItem::start));
            for (int i = 1; i < timedItems.size(); i++) {
                TimedItem previous = timedItems.get(i - 1);
                TimedItem current = timedItems.get(i);
                if (current.start().isBefore(previous.end())) {
                    addError(errors, "第 " + displayDay(dayNo) + " 天活动时间重叠");
                    break;
                }
            }
        }

        for (int dayNo = 1; dayNo <= expectedDays; dayNo++) {
            if (!dayNos.contains(dayNo)) {
                addError(errors, "缺少第 " + dayNo + " 天");
            }
        }
        if (!candidates.isEmpty() && !selectedCandidate) {
            addError(errors, "存在候选地点但模型未选择任何候选地点");
        }

        BigDecimal userBudget = request == null ? BigDecimal.ZERO : BigDecimal.valueOf(request.getBudget());
        if (userBudget.signum() > 0 && verifiedTotal.compareTo(userBudget) > 0) {
            addError(errors, "按工具价格核验后的已知费用已超过用户预算");
        }
        if (plan.getTotalCost() != null && plan.getTotalCost().signum() < 0) {
            addError(errors, "totalCost 不能为负数");
        }
        // 模型声称超预算时直接判无效；模型声称未超预算不作为通过依据。
        if (userBudget.signum() > 0 && plan.getTotalCost() != null
                && plan.getTotalCost().compareTo(userBudget) > 0) {
            addError(errors, "模型返回的 totalCost 超过用户预算");
        }

        return new ValidationResult(errors.isEmpty(), List.copyOf(errors), verifiedTotal,
                hasUnknownCosts, Map.copyOf(candidates));
    }

    public Map<String, CandidatePlace> indexCandidates(TravelPlanResult toolResult) {
        Map<String, CandidatePlace> candidates = new LinkedHashMap<>();
        if (toolResult == null) return candidates;

        if (toolResult.getPois() != null) {
            for (PoiResult poi : toolResult.getPois()) {
                String name = sanitizeExternalText(poi == null ? null : poi.getName());
                if (poi != null && hasText(poi.getPlaceId()) && hasText(name)) {
                    candidates.putIfAbsent(poi.getPlaceId(), new CandidatePlace(
                            poi.getPlaceId(), name, sanitizeExternalText(poi.getAddress()),
                            LlmItineraryPlan.ItemType.ATTRACTION, null));
                }
            }
        }
        if (toolResult.getMeals() != null) {
            for (MealResult meal : toolResult.getMeals()) {
                String name = sanitizeExternalText(meal == null ? null : meal.getName());
                if (meal != null && hasText(meal.getPlaceId()) && hasText(name)) {
                    BigDecimal unitCost = meal.getAvgPrice() == null || meal.getAvgPrice() < 0
                            ? null : BigDecimal.valueOf(meal.getAvgPrice());
                    candidates.putIfAbsent(meal.getPlaceId(), new CandidatePlace(
                            meal.getPlaceId(), name, sanitizeExternalText(meal.getAddress()),
                            LlmItineraryPlan.ItemType.RESTAURANT, unitCost));
                }
            }
        }
        return candidates;
    }

    /**
     * 从未通过整体校验的模型结果中保留可证明安全的片段。
     *
     * <p>这不是“放宽校验”：每个留下的活动仍然必须有合法时间、合法类型，且地点
     * 必须来自工具候选集；冲突、重复、候选集外地点和错误日期都会被丢弃。缺失天数
     * 用服务端已生成的日期补齐为空天，交给确定性渲染器展示工具数据或“暂无可靠数据”。</p>
     */
    public LlmItineraryPlan retainSafeFragments(LlmItineraryPlan plan,
                                                TravelPlanRequest request,
                                                TravelPlanResult toolResult) {
        if (plan == null || request == null || plan.getDays() == null) return null;

        int expectedDays = request.getDays();
        Map<String, CandidatePlace> candidates = indexCandidates(toolResult);
        Map<Integer, LlmItineraryPlan.Day> sourceDays = new LinkedHashMap<>();
        for (LlmItineraryPlan.Day sourceDay : plan.getDays()) {
            if (sourceDay == null || sourceDay.getDayNo() == null
                    || sourceDay.getDayNo() < 1 || sourceDay.getDayNo() > expectedDays
                    || sourceDays.containsKey(sourceDay.getDayNo())
                    || sourceDay.getDate() == null
                    || !matchesExpectedDate(sourceDay.getDayNo(), sourceDay.getDate(), toolResult)) {
                continue;
            }
            sourceDays.put(sourceDay.getDayNo(), sourceDay);
        }

        Set<String> usedPlaceIds = new HashSet<>();
        BigDecimal budget = request.getBudget() > 0
                ? BigDecimal.valueOf(request.getBudget()) : BigDecimal.ZERO;
        int travelers = Math.max(1, request.getTravelers());
        BigDecimal[] retainedKnownCost = {BigDecimal.ZERO};
        List<LlmItineraryPlan.Day> retainedDays = new ArrayList<>();
        for (int dayNo = 1; dayNo <= expectedDays; dayNo++) {
            LlmItineraryPlan.Day sourceDay = sourceDays.get(dayNo);
            List<LlmItineraryPlan.Item> retainedItems = sourceDay == null
                    ? List.of()
                    : retainSafeItems(sourceDay.getItems(), candidates, usedPlaceIds,
                    budget, travelers, retainedKnownCost);
            LocalDate date = expectedDate(dayNo, toolResult);
            if (date == null && sourceDay != null) date = sourceDay.getDate();
            retainedDays.add(new LlmItineraryPlan.Day(dayNo, date, retainedItems, null));
        }
        return new LlmItineraryPlan(SCHEMA_VERSION, retainedDays, null);
    }

    private List<LlmItineraryPlan.Item> retainSafeItems(List<LlmItineraryPlan.Item> sourceItems,
                                                        Map<String, CandidatePlace> candidates,
                                                        Set<String> usedPlaceIds,
                                                        BigDecimal budget,
                                                        int travelers,
                                                        BigDecimal[] retainedKnownCost) {
        if (sourceItems == null || sourceItems.isEmpty()) return List.of();

        List<LlmItineraryPlan.Item> retained = new ArrayList<>();
        for (LlmItineraryPlan.Item item : sourceItems) {
            if (item == null || item.getType() == null
                    || item.getStartTime() == null || item.getEndTime() == null
                    || !item.getStartTime().isBefore(item.getEndTime())) {
                continue;
            }

            if (item.getType() == LlmItineraryPlan.ItemType.TRANSPORT) {
                if (hasText(item.getPlaceId()) || overlaps(item, retained)) continue;
            } else {
                if (!hasText(item.getPlaceId())) continue;
                CandidatePlace candidate = candidates.get(item.getPlaceId());
                if (candidate == null || candidate.type() != item.getType()
                        || overlaps(item, retained)) {
                    continue;
                }
                if (candidate.unitCost() != null && budget.signum() > 0) {
                    BigDecimal itemCost = candidate.unitCost()
                            .multiply(BigDecimal.valueOf(travelers));
                    if (retainedKnownCost[0].add(itemCost).compareTo(budget) > 0) continue;
                }
                if (!usedPlaceIds.add(item.getPlaceId())) continue;
                if (candidate.unitCost() != null) {
                    retainedKnownCost[0] = retainedKnownCost[0].add(candidate.unitCost()
                            .multiply(BigDecimal.valueOf(travelers)));
                }
            }

            // 不保留模型自带的费用/说明，避免部分兜底路径再次信任未核验内容。
            retained.add(new LlmItineraryPlan.Item(
                    item.getType(), item.getPlaceId(), item.getStartTime(), item.getEndTime(), null, null));
        }
        return List.copyOf(retained);
    }

    private boolean overlaps(LlmItineraryPlan.Item item,
                              List<LlmItineraryPlan.Item> retained) {
        for (LlmItineraryPlan.Item existing : retained) {
            if (item.getStartTime().isBefore(existing.getEndTime())
                    && existing.getStartTime().isBefore(item.getEndTime())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesExpectedDate(int dayNo,
                                        LocalDate actualDate,
                                        TravelPlanResult toolResult) {
        LocalDate expected = expectedDate(dayNo, toolResult);
        return expected == null || expected.equals(actualDate);
    }

    private LocalDate expectedDate(int dayNo, TravelPlanResult toolResult) {
        if (toolResult == null || toolResult.getDayPlans() == null
                || dayNo < 1 || dayNo > toolResult.getDayPlans().size()) {
            return null;
        }
        DayPlan day = toolResult.getDayPlans().get(dayNo - 1);
        if (day == null || day.getDate() == null || day.getDate().isBlank()) return null;
        try {
            return LocalDate.parse(day.getDate());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void validateDate(LlmItineraryPlan.Day day,
                              Integer dayNo,
                              TravelPlanResult toolResult,
                              List<String> errors) {
        if (toolResult == null || toolResult.getDayPlans() == null || dayNo == null
                || dayNo < 1 || dayNo > toolResult.getDayPlans().size()) {
            return;
        }
        DayPlan expected = toolResult.getDayPlans().get(dayNo - 1);
        if (expected != null && expected.getDate() != null
                && !expected.getDate().equals(day.getDate().toString())) {
            addError(errors, "第 " + dayNo + " 天 date 与服务端计划不一致");
        }
    }

    private void validateCostShape(LlmItineraryPlan.Item item, List<String> errors) {
        if (item.getEstimatedCost() != null && item.getEstimatedCost().signum() < 0) {
            addError(errors, "estimatedCost 不能为负数");
        }
    }

    private ValidationResult invalid(List<String> errors,
                                     Map<String, CandidatePlace> candidates,
                                     BigDecimal verifiedTotal,
                                     boolean hasUnknownCosts) {
        return new ValidationResult(false, List.copyOf(errors), verifiedTotal,
                hasUnknownCosts, Map.copyOf(candidates));
    }

    private static void addError(List<String> errors, String error) {
        if (errors.size() < MAX_ERRORS) errors.add(error);
    }

    private static String displayDay(Integer dayNo) {
        return dayNo == null ? "未知" : dayNo.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 清理工具返回的展示文本。该方法不是提示词安全边界，真正的边界仍是
     * UNTRUSTED_TOOL_DATA 封装和后续候选 ID 校验；这里只移除控制字符并限制长度，
     * 避免外部内容污染提示词或最终 Markdown。
     */
    private static String sanitizeExternalText(String value) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('<', '＜')
                .replace('>', '＞')
                .replaceAll("[\\p{Cntrl}&&[^\\t\\r\\n]]", "")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() <= MAX_EXTERNAL_TEXT_LENGTH) return normalized;
        return normalized.substring(0, MAX_EXTERNAL_TEXT_LENGTH);
    }

    public record CandidatePlace(String placeId,
                                 String name,
                                 String address,
                                 LlmItineraryPlan.ItemType type,
                                 BigDecimal unitCost) {
    }

    public record ValidationResult(boolean valid,
                                   List<String> errors,
                                   BigDecimal verifiedTotal,
                                   boolean hasUnknownCosts,
                                   Map<String, CandidatePlace> candidates) {
    }

    private record TimedItem(LocalTime start, LocalTime end) {
    }
}
