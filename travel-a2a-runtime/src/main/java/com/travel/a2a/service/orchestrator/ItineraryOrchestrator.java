package com.travel.a2a.service.orchestrator;

import com.travel.a2a.model.*;
import com.travel.a2a.config.A2aRuntimeProperties;
import com.travel.a2a.service.subagent.BudgetSubAgent;
import com.travel.a2a.service.subagent.MealSubAgent;
import com.travel.a2a.service.subagent.PoiSubAgent;
import com.travel.a2a.service.subagent.WeatherSubAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 行程编排器
 * 
 * <p>负责并行调用 4 个子 Agent（weather/poi/meal/budget），
 * 收集结果并编排每日行程。每个工具独立超时/降级，单个失败不影响其它结果。</p>
 */
@Slf4j
@Service
public class ItineraryOrchestrator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final WeatherSubAgent weatherSubAgent;
    private final PoiSubAgent poiSubAgent;
    private final MealSubAgent mealSubAgent;
    private final BudgetSubAgent budgetSubAgent;
    private final ExecutorService executor;
    private final Duration toolTimeout;
    private final Duration aggregateTimeout;

    public ItineraryOrchestrator(WeatherSubAgent weatherSubAgent,
                                 PoiSubAgent poiSubAgent,
                                 MealSubAgent mealSubAgent,
                                 BudgetSubAgent budgetSubAgent,
                                 @Qualifier("orchestratorExecutor") ExecutorService executor,
                                 A2aRuntimeProperties runtimeProperties) {
        this.weatherSubAgent = weatherSubAgent;
        this.poiSubAgent = poiSubAgent;
        this.mealSubAgent = mealSubAgent;
        this.budgetSubAgent = budgetSubAgent;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.toolTimeout = positive(runtimeProperties.getToolTimeout(), Duration.ofSeconds(10));
        this.aggregateTimeout = positive(runtimeProperties.getAggregateTimeout(), Duration.ofSeconds(30));
    }

    /**
     * 执行行程编排
     *
     * @param request 行程请求
     * @return 行程结果
     */
    public TravelPlanResult orchestrate(TravelPlanRequest request) {
        log.info("ItineraryOrchestrator: 开始编排行程, destination={}, days={}",
                request.getDestination(), request.getDays());

        // 先将每个工具包装成“永不异常完成”的安全 Future，再交给 allOf。
        // 这样 weather 失败时，poi/meal/budget 仍会被收集并用于生成计划。
        BoundedToolCall weatherCall = submitTool("weather",
                () -> weatherSubAgent.getWeather(request.getDestination()));
        BoundedToolCall poiCall = submitTool("poi", () -> poiSubAgent.search(request));
        BoundedToolCall mealCall = submitTool("meal", () -> mealSubAgent.search(request));
        BoundedToolCall budgetCall = submitTool("budget", () -> budgetSubAgent.estimate(request));

        List<BoundedToolCall> calls = List.of(weatherCall, poiCall, mealCall, budgetCall);
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(calls.stream()
                .map(BoundedToolCall::resultFuture)
                .toArray(CompletableFuture[]::new));

        boolean aggregateTimedOut = false;
        try {
            // copy() 让 aggregate timeout 不会覆盖安全 Future 本身；超时后仍可逐个快照收集。
            allFutures.copy()
                    .orTimeout(aggregateTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException | CancellationException e) {
            aggregateTimedOut = true;
            log.warn("ItineraryOrchestrator: 子 Agent 聚合等待超时或异常, timeoutMs={}, cause={}",
                    aggregateTimeout.toMillis(), safeThrowableMessage(e));
        }

        // 先收集聚合截止时已经完成的结果；readAgentResult 不会阻塞。
        // 必须在 cancel 之前做快照，否则未完成结果会被误标记为“取消”而不是“总超时”。
        List<DataWarning> warnings = new ArrayList<>();
        WeatherResult weather = collectWeather(weatherCall.resultFuture(), warnings);
        List<PoiResult> pois = collectPois(poiCall.resultFuture(), warnings);
        List<MealResult> meals = collectMeals(mealCall.resultFuture(), warnings);
        BudgetEstimate budget = collectBudget(budgetCall.resultFuture(), warnings);

        if (aggregateTimedOut) {
            // cancel(true) 只是中断信号，真正释放 HTTP 连接依赖传输层自己的请求/读超时。
            calls.forEach(BoundedToolCall::cancelTask);
        }

        List<DayPlan> dayPlans = buildDayPlans(request, weather, pois, meals, budget);

        log.info("ItineraryOrchestrator: 编排完成, dayPlans={}, warnings={}",
                dayPlans.size(), warnings.size());

        return TravelPlanResult.builder()
                .success(true)
                .destination(request.getDestination())
                .days(request.getDays())
                .travelers(request.getTravelers())
                .travelStyle(request.getTravelStyle())
                .interests(request.getInterests())
                .strategyNotes(List.of("按天气调整户外与室内比例", "按区域串联地点，减少折返", "根据偏好平衡餐饮与景点预算"))
                .dayPlans(dayPlans)
                .dataWarnings(warnings)
                .weather(weather)
                .pois(pois)
                .meals(meals)
                .budget(budget)
                .build();
    }

    private BoundedToolCall submitTool(String source, Supplier<AgentResult> supplier) {
        long startNanos = System.nanoTime();
        CompletableFuture<AgentResult> taskFuture;
        Future<?> executionFuture;
        taskFuture = new CompletableFuture<>();
        try {
            executionFuture = executor.submit(() -> {
                if (taskFuture.isCancelled()) {
                    return;
                }
                try {
                    taskFuture.complete(supplier.get());
                } catch (Throwable throwable) {
                    taskFuture.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("ItineraryOrchestrator: 工具任务被拒绝, source={}", source);
            AgentResult failed = AgentResult.failure(source, "系统繁忙，该工具暂不可用",
                    elapsedMillis(startNanos));
            CompletableFuture<AgentResult> failedFuture = CompletableFuture.completedFuture(failed);
            return new BoundedToolCall(failedFuture, failedFuture, failedFuture);
        }

        // 对 copy 设置超时，保留原始任务 Future 以便超时时向底层任务发送 cancel(true)。
        CompletableFuture<AgentResult> resultFuture = taskFuture.copy()
                .orTimeout(toolTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((result, throwable) -> {
                    long elapsedMs = elapsedMillis(startNanos);
                    if (throwable == null && result != null) {
                        return result;
                    }

                    Throwable cause = throwable == null ? null : unwrap(throwable);
                    if (cause instanceof TimeoutException) {
                        log.warn("ItineraryOrchestrator: 工具调用超时, source={}, timeoutMs={}",
                                source, toolTimeout.toMillis());
                        executionFuture.cancel(true);
                        taskFuture.cancel(false);
                        return AgentResult.failure(source,
                                "调用超时（" + toolTimeout.toSeconds() + "秒）", elapsedMs);
                    }
                    if (cause instanceof CancellationException) {
                        log.warn("ItineraryOrchestrator: 工具调用被取消, source={}", source);
                        return AgentResult.failure(source, "调用已取消", elapsedMs);
                    }

                    log.warn("ItineraryOrchestrator: 工具调用异常，已降级, source={}, cause={}",
                            source, safeThrowableMessage(cause));
                    executionFuture.cancel(true);
                    taskFuture.cancel(false);
                    return AgentResult.failure(source, "调用异常，已降级", elapsedMs);
                });
        return new BoundedToolCall(taskFuture, resultFuture, executionFuture);
    }

    private AgentResult readAgentResult(String source,
                                        CompletableFuture<AgentResult> future,
                                        List<DataWarning> warnings) {
        if (!future.isDone()) {
            String message = "聚合超时，未收到结果";
            addWarning(source, message, aggregateTimeout.toMillis(), warnings);
            return AgentResult.failure(source, message, aggregateTimeout.toMillis());
        }

        try {
            AgentResult result = future.join();
            if (result == null) {
                String message = "工具未返回有效结果";
                addWarning(source, message, 0, warnings);
                return AgentResult.failure(source, message, 0);
            }
            if (!result.isSuccess()) {
                addWarning(source, safeMessage(result.getError()), result.getElapsedMs(), warnings);
            }
            return result;
        } catch (CompletionException | CancellationException e) {
            log.warn("ItineraryOrchestrator: 读取工具结果异常, source={}, cause={}",
                    source, safeThrowableMessage(e));
            String message = "工具调用异常，已降级";
            addWarning(source, message, aggregateTimeout.toMillis(), warnings);
            return AgentResult.failure(source, message, aggregateTimeout.toMillis());
        }
    }

    private void addWarning(String source, String message, long elapsedMs, List<DataWarning> warnings) {
        warnings.add(DataWarning.builder()
                .source(source)
                .message(safeMessage(message))
                .elapsedMs(Math.max(0, elapsedMs))
                .build());
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static Duration positive(Duration duration, Duration fallback) {
        return duration == null || duration.isZero() || duration.isNegative() ? fallback : duration;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeThrowableMessage(Throwable throwable) {
        if (throwable == null) return "unknown";
        String type = throwable.getClass().getSimpleName();
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? type : type + ": " + safeMessage(message);
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "工具调用失败";
        String compact = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return compact.length() > 240 ? compact.substring(0, 240) + "…" : compact;
    }

    private record BoundedToolCall(CompletableFuture<AgentResult> taskFuture,
                                   CompletableFuture<AgentResult> resultFuture,
                                   Future<?> executionFuture) {
        private void cancelTask() {
            if (!executionFuture.isDone()) {
                executionFuture.cancel(true);
            }
            if (!taskFuture.isDone()) {
                taskFuture.cancel(false);
            }
        }
    }

    /**
     * 收集天气结果
     */
    private WeatherResult collectWeather(CompletableFuture<AgentResult> future,
                                          List<DataWarning> warnings) {
        AgentResult result = readAgentResult("weather", future, warnings);
        if (result.isSuccess() && result.getData() instanceof WeatherResult weather) {
            return weather;
        }
        if (result.isSuccess()) {
            addWarning("weather", "返回数据格式异常", result.getElapsedMs(), warnings);
        }

        return WeatherResult.builder()
                .success(false)
                .city("未知")
                .build();
    }

    /**
     * 收集 POI 结果
     */
    @SuppressWarnings("unchecked")
    private List<PoiResult> collectPois(CompletableFuture<AgentResult> future,
                                        List<DataWarning> warnings) {
        AgentResult result = readAgentResult("poi", future, warnings);
        if (result.isSuccess() && result.getData() instanceof List<?> list) {
            return (List<PoiResult>) list;
        }
        if (result.isSuccess()) {
            addWarning("poi", "返回数据格式异常", result.getElapsedMs(), warnings);
        }
        return new ArrayList<>();
    }

    /**
     * 收集餐饮结果
     */
    @SuppressWarnings("unchecked")
    private List<MealResult> collectMeals(CompletableFuture<AgentResult> future,
                                          List<DataWarning> warnings) {
        AgentResult result = readAgentResult("meal", future, warnings);
        if (result.isSuccess() && result.getData() instanceof List<?> list) {
            return (List<MealResult>) list;
        }
        if (result.isSuccess()) {
            addWarning("meal", "返回数据格式异常", result.getElapsedMs(), warnings);
        }
        return new ArrayList<>();
    }

    /**
     * 收集预算结果
     */
    private BudgetEstimate collectBudget(CompletableFuture<AgentResult> future,
                                         List<DataWarning> warnings) {
        AgentResult result = readAgentResult("budget", future, warnings);
        if (result.isSuccess() && result.getData() instanceof BudgetEstimate budget) {
            return budget;
        }
        if (result.isSuccess()) {
            addWarning("budget", "返回数据格式异常", result.getElapsedMs(), warnings);
        }

        return BudgetEstimate.builder()
                .success(false)
                .totalBudget(0)
                .perPersonBudget(0)
                .breakdown(new ArrayList<>())
                .build();
    }

    /**
     * 构建每日行程
     */
    private List<DayPlan> buildDayPlans(TravelPlanRequest request,
                                         WeatherResult weather,
                                         List<PoiResult> pois,
                                         List<MealResult> meals,
                                         BudgetEstimate budget) {
        List<DayPlan> dayPlans = new ArrayList<>();
        int days = request.getDays();
        double totalBudget = budget.isSuccess() && budget.getTotalBudget() > 0 ? budget.getTotalBudget() : request.getBudget();
        double[] weights = {0.82, 1.08, 1.22, 0.94, 1.16};
        double weightTotal = 0;
        for (int i = 0; i < days; i++) weightTotal += weights[Math.min(i, weights.length - 1)];

        LocalDate startDate = LocalDate.now().plusDays(1);

        for (int i = 0; i < days; i++) {
            double dailyBudget = Math.round(totalBudget * weights[Math.min(i, weights.length - 1)] / weightTotal);
            DayPlan.DayPlanBuilder dayPlanBuilder = DayPlan.builder()
                    .day(i + 1)
                    .date(startDate.plusDays(i).format(DATE_FORMATTER))
                    .theme(themeFor(request, i))
                    .dailyBudget(dailyBudget);

            // 只使用天气工具实际返回的字段；失败或缺字段时保持未知，不填充虚构天气。
            applyWeather(dayPlanBuilder, weather, i);

            // 编排当日活动
            List<DayPlan.Activity> activities = buildDailyActivities(
                    i, days, pois, meals, request);
            dayPlanBuilder.activities(activities);

            dayPlans.add(dayPlanBuilder.build());
        }

        return dayPlans;
    }

    /**
     * 将 MCP 返回的真实天气映射到当天计划。天气缺失时不使用硬编码默认值。
     */
    private void applyWeather(DayPlan.DayPlanBuilder builder, WeatherResult weather, int dayIndex) {
        if (weather == null || !weather.isSuccess() || !(weather.getData() instanceof Map<?, ?> data)) {
            return;
        }

        Object forecastValue = data.get("forecast");
        if (!(forecastValue instanceof List<?> forecast) || dayIndex >= forecast.size()) {
            return;
        }
        Object dailyValue = forecast.get(dayIndex);
        if (!(dailyValue instanceof Map<?, ?> daily)) {
            return;
        }

        String text = textValue(daily.get("text"));
        Integer min = integerValue(daily.get("tempMin"));
        Integer max = integerValue(daily.get("tempMax"));
        if (text != null) {
            builder.weather(text);
        }
        if (min != null && max != null) {
            builder.temperature(min + "-" + max + "℃");
        } else if (min != null) {
            builder.temperature(min + "℃");
        } else if (max != null) {
            builder.temperature(max + "℃");
        }
    }

    private String textValue(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String themeFor(TravelPlanRequest request, int day) {
        String style = request.getTravelStyle();
        if (style == null || style.isBlank()) style = "轻松漫游";
        String[] themes = {"城市初见与核心地标", "在地文化与美食探索", "慢节奏收束与自由漫游"};
        return themes[Math.min(day, themes.length - 1)] + " · " + style;
    }

    /**
     * 构建当日活动
     * <p>优先用去重后的景点/餐厅，每天配 2 个不同景点 + 2 个不同餐厅，
     * 并按天错开索引，避免同一天/相邻天重复同一地点导致图片重复。</p>
     */
    private List<DayPlan.Activity> buildDailyActivities(int dayIndex,
                                                         int totalDays,
                                                         List<PoiResult> pois,
                                                         List<MealResult> meals,
                                                         TravelPlanRequest request) {
        List<DayPlan.Activity> activities = new ArrayList<>();

        List<PoiResult> uniqPois = distinctByName(pois);
        List<MealResult> uniqMeals = distinctByName(meals);

        // 上午：景点1（每天取不同的索引，错开重复）
        PoiResult poi1 = pick(uniqPois, dayIndex * 2);
        if (poi1 != null) {
            activities.add(activity("09:00", "sightseeing", poi1.getPlaceId(), poi1.getName(), poi1.getAddress(),
                    150, null, "景点 · 建议上午前往，错峰游览；门票费用暂无数据"));
        }

        // 午餐
        MealResult lunch = pick(uniqMeals, dayIndex * 2);
        if (lunch != null) {
            activities.add(activity("12:00", "meal", lunch.getPlaceId(), lunch.getName(), lunch.getAddress(),
                    90, mealCost(lunch), "午餐 · 当地特色美食；费用按已返回的人均价格展示"));
        }

        // 下午：景点2（与上午不同）
        PoiResult poi2 = pick(uniqPois, dayIndex * 2 + 1);
        if (poi2 != null && !sameName(poi1, poi2)) {
            activities.add(activity("14:00", "sightseeing", poi2.getPlaceId(), poi2.getName(), poi2.getAddress(),
                    150, null, "景点 · 下午光线好，适合游览打卡；门票费用暂无数据"));
        }

        // 晚餐
        MealResult dinner = pick(uniqMeals, dayIndex * 2 + 1);
        if (dinner != null && !sameName(lunch, dinner)) {
            activities.add(activity("18:00", "meal", dinner.getPlaceId(), dinner.getName(), dinner.getAddress(),
                    90, mealCost(dinner), "晚餐 · 结束一天的行程；费用按已返回的人均价格展示"));
        }

        // 晚上休息
        activities.add(activity("20:00", "rest", null, "返回酒店休息", request.getDestination(),
                0, 0d, "好好休息，明天继续探索"));

        return activities;
    }

    /** 按名称去重（名称相同视为同一点，避免重复地点/重复图片） */
    private <T> List<T> distinctByName(List<T> list) {
        if (list == null) return new ArrayList<>();
        List<T> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (T item : list) {
            String n = poiName(item);
            if (n == null || n.isBlank() || seen.add(n)) out.add(item);
        }
        return out;
    }

    /** 取列表第 idx 个元素；不循环复用，避免跨天重复地点。 */
    private <T> T pick(List<T> list, int idx) {
        if (list == null || list.isEmpty()) return null;
        return idx >= 0 && idx < list.size() ? list.get(idx) : null;
    }

    private <T> String poiName(T item) {
        if (item instanceof PoiResult p) return p.getName();
        if (item instanceof MealResult m) return m.getName();
        return null;
    }

    private <T> boolean sameName(T a, T b) {
        if (a == null || b == null) return false;
        String na = poiName(a), nb = poiName(b);
        return na != null && na.equals(nb);
    }

    private DayPlan.Activity activity(String time, String type, String placeId, String name, String loc,
                                      int dur, Double cost, String note) {
        return DayPlan.Activity.builder()
                .time(time)
                .type(type)
                .name(name == null ? "" : name)
                .placeId(placeId)
                .location(loc == null ? "" : loc)
                .transport(type.equals("rest") ? "返回酒店" : "公共交通 / 步行优先")
                .duration(dur)
                .cost(cost)
                .notes(note)
                .build();
    }

    private Double mealCost(MealResult meal) {
        return meal == null || meal.getAvgPrice() == null || meal.getAvgPrice() < 0
                ? null : meal.getAvgPrice().doubleValue();
    }
}
