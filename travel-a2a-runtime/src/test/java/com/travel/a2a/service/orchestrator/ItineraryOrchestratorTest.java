package com.travel.a2a.service.orchestrator;

import com.travel.a2a.config.A2aRuntimeProperties;
import com.travel.a2a.model.AgentResult;
import com.travel.a2a.model.BudgetEstimate;
import com.travel.a2a.model.MealResult;
import com.travel.a2a.model.PoiResult;
import com.travel.a2a.model.TravelPlanRequest;
import com.travel.a2a.model.TravelPlanResult;
import com.travel.a2a.model.WeatherResult;
import com.travel.a2a.service.subagent.BudgetSubAgent;
import com.travel.a2a.service.subagent.MealSubAgent;
import com.travel.a2a.service.subagent.PoiSubAgent;
import com.travel.a2a.service.subagent.WeatherSubAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
class ItineraryOrchestratorTest {

    private ExecutorService executor;
    private ItineraryOrchestrator orchestrator;

    void setUp(A2aRuntimeProperties properties,
               WeatherSubAgent weatherSubAgent,
               PoiSubAgent poiSubAgent,
               MealSubAgent mealSubAgent,
               BudgetSubAgent budgetSubAgent) {
        executor = Executors.newFixedThreadPool(4);
        orchestrator = new ItineraryOrchestrator(
                weatherSubAgent,
                poiSubAgent,
                mealSubAgent,
                budgetSubAgent,
                executor,
                properties);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void weatherFailureDoesNotDiscardOtherSuccessfulTools() {
        TravelPlanRequest request = request();
        List<PoiResult> pois = List.of(PoiResult.builder().name("西湖").address("西湖景区").build());
        List<MealResult> meals = List.of(MealResult.builder().name("本地餐厅").address("湖滨路").build());
        BudgetEstimate budget = BudgetEstimate.builder()
                .success(true)
                .totalBudget(1_000)
                .perPersonBudget(500)
                .breakdown(List.of())
                .build();

        setUp(new A2aRuntimeProperties(),
                weatherSubAgent(city -> {
                    throw new IllegalStateException("weather provider unavailable");
                }),
                poiSubAgent(ignored -> AgentResult.success("poi", pois, 20)),
                mealSubAgent(ignored -> AgentResult.success("meal", meals, 30)),
                budgetSubAgent(ignored -> AgentResult.success("budget", budget, 10)));

        TravelPlanResult result = orchestrator.orchestrate(request);

        assertTrue(result.isSuccess());
        assertEquals(pois, result.getPois());
        assertEquals(meals, result.getMeals());
        assertTrue(result.getBudget().isSuccess());
        assertFalse(result.getWeather().isSuccess());
        assertTrue(result.getDataWarnings().stream()
                .anyMatch(warning -> "weather".equals(warning.getSource())));
    }

    @Test
    void timedOutToolIsConvertedToWarningWithoutBlockingAggregation() throws Exception {
        A2aRuntimeProperties properties = new A2aRuntimeProperties();
        properties.setToolTimeout(Duration.ofMillis(100));
        properties.setAggregateTimeout(Duration.ofSeconds(1));

        CountDownLatch release = new CountDownLatch(1);
        setUp(properties,
                weatherSubAgent(ignored -> {
                    try {
                        while (!release.await(50, TimeUnit.MILLISECONDS)) {
                            // 模拟无法立即响应中断的底层阻塞；HTTP 层超时负责最终释放资源。
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return AgentResult.failure("weather", "released", 0);
                }),
                poiSubAgent(ignored -> AgentResult.success("poi", List.of(), 10)),
                mealSubAgent(ignored -> AgentResult.success("meal", List.of(), 10)),
                budgetSubAgent(ignored -> AgentResult.success("budget", BudgetEstimate.builder()
                        .success(true).totalBudget(1_000).perPersonBudget(500).breakdown(List.of()).build(), 10)));

        try {
            long start = System.nanoTime();
            TravelPlanResult result = orchestrator.orchestrate(request());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(result.isSuccess());
            assertTrue(elapsedMs < 900, "单工具超时后不应等待底层任务完成");
            assertTrue(result.getDataWarnings().stream()
                    .anyMatch(warning -> "weather".equals(warning.getSource())
                            && warning.getMessage().contains("超时")));
        } finally {
            release.countDown();
        }
    }

    @Test
    void aggregateTimeoutSnapshotsCompletedResultsBeforeCancellingPendingTools() {
        A2aRuntimeProperties properties = new A2aRuntimeProperties();
        properties.setToolTimeout(Duration.ofSeconds(5));
        properties.setAggregateTimeout(Duration.ofMillis(100));

        CountDownLatch release = new CountDownLatch(1);
        setUp(properties,
                weatherSubAgent(ignored -> AgentResult.success("weather", WeatherResult.builder()
                        .city("杭州").success(true).data(Map.of()).build(), 10)),
                poiSubAgent(ignored -> AgentResult.success("poi", List.of(
                        PoiResult.builder().placeId("poi:1").name("西湖").address("西湖景区").build()), 10)),
                mealSubAgent(ignored -> waitForRelease(release, "meal")),
                budgetSubAgent(ignored -> waitForRelease(release, "budget")));

        try {
            long start = System.nanoTime();
            TravelPlanResult result = orchestrator.orchestrate(request());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(result.isSuccess());
            assertTrue(elapsedMs < 900, "聚合超时后不应等待未完成工具");
            assertTrue(result.getWeather().isSuccess(), "已完成的天气结果必须保留");
            assertEquals(1, result.getPois().size(), "已完成的 POI 结果必须保留");
            assertTrue(result.getMeals().isEmpty());
            assertFalse(result.getBudget().isSuccess());
            assertTrue(result.getDataWarnings().stream()
                    .anyMatch(warning -> "meal".equals(warning.getSource())
                            && warning.getMessage().contains("聚合超时")));
            assertTrue(result.getDataWarnings().stream()
                    .anyMatch(warning -> "budget".equals(warning.getSource())
                            && warning.getMessage().contains("聚合超时")));
        } finally {
            release.countDown();
        }
    }

    private AgentResult waitForRelease(CountDownLatch release, String source) {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return AgentResult.failure(source, "released", 0);
    }

    private TravelPlanRequest request() {
        return TravelPlanRequest.builder()
                .destination("杭州")
                .days(1)
                .budget(1_000)
                .travelers(2)
                .travelStyle("休闲")
                .interests(List.of("自然"))
                .build();
    }

    private WeatherSubAgent weatherSubAgent(Function<String, AgentResult> action) {
        return new WeatherSubAgent(null) {
            @Override
            public AgentResult getWeather(String city) {
                return action.apply(city);
            }
        };
    }

    private PoiSubAgent poiSubAgent(Function<TravelPlanRequest, AgentResult> action) {
        return new PoiSubAgent(null) {
            @Override
            public AgentResult search(TravelPlanRequest request) {
                return action.apply(request);
            }
        };
    }

    private MealSubAgent mealSubAgent(Function<TravelPlanRequest, AgentResult> action) {
        return new MealSubAgent(null) {
            @Override
            public AgentResult search(TravelPlanRequest request) {
                return action.apply(request);
            }
        };
    }

    private BudgetSubAgent budgetSubAgent(Function<TravelPlanRequest, AgentResult> action) {
        return new BudgetSubAgent(null) {
            @Override
            public AgentResult estimate(TravelPlanRequest request) {
                return action.apply(request);
            }
        };
    }
}
