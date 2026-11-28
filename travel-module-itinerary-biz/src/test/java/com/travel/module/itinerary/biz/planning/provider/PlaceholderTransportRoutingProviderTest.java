package com.travel.module.itinerary.biz.planning.provider;

import com.travel.module.itinerary.biz.planning.provider.TransportRoutingProvider.DayStopsInput;
import com.travel.module.itinerary.biz.planning.provider.TransportRoutingProvider.DayTransportPlan;
import com.travel.module.itinerary.biz.planning.provider.TransportRoutingProvider.StopInput;
import com.travel.module.itinerary.biz.planning.provider.TransportRoutingProvider.TransportSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderTransportRoutingProviderTest {

    private PlaceholderTransportRoutingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new PlaceholderTransportRoutingProvider();
    }

    @Test
    void estimatesDeterministicSegmentsWithValidModesAndFares() {
        DayStopsInput day1 = new DayStopsInput(1, List.of(
                new StopInput("西湖断桥", "杭州市西湖区北山街", "sightseeing"),
                new StopInput("楼外楼", "杭州市西湖区孤山路", "meal"),
                new StopInput("灵隐寺", "杭州市西湖区法云弄", "sightseeing")
        ));

        List<DayTransportPlan> firstRun = provider.estimateDays("杭州", List.of(day1));
        List<DayTransportPlan> secondRun = provider.estimateDays("杭州", List.of(day1));

        assertEquals(1, firstRun.size());
        DayTransportPlan plan = firstRun.get(0);
        assertEquals(2, plan.segments().size());
        assertEquals(plan, secondRun.get(0));

        for (TransportSegment seg : plan.segments()) {
            assertEquals("PLACEHOLDER_ROUTING", seg.dataSource());
            assertTrue(seg.distanceKm() > 0);
            assertTrue(seg.durationMinutes() >= 6);
            if ("步行".equals(seg.recommendedMode())) {
                assertEquals(0, seg.estimatedFare());
            } else {
                assertTrue(seg.estimatedFare() > 0);
            }
        }
    }

    @Test
    void triggersRouteWarningWhenDailyCommuteIsExcessive() {
        DayStopsInput heavyDay = new DayStopsInput(1, List.of(
                new StopInput("天安门广场", "北京市东城区", "sightseeing"),
                new StopInput("颐和园", "北京市海淀区", "sightseeing"),
                new StopInput("八达岭长城", "北京市延庆区", "sightseeing"),
                new StopInput("鸟巢", "北京市朝阳区", "sightseeing"),
                new StopInput("首钢园", "北京市石景山区", "sightseeing"),
                new StopInput("大兴野生动物园", "北京市大兴区", "sightseeing"),
                new StopInput("环球度假区", "北京市通州区", "sightseeing")
        ));

        DayTransportPlan plan = provider.estimateDays("北京", List.of(heavyDay)).get(0);

        assertEquals(6, plan.segments().size());
        assertTrue(plan.totalDurationMinutes() > 95 || plan.totalDistanceKm() > 28.0);
        assertNotNull(plan.routeWarning());
        assertTrue(plan.routeWarning().contains("跨区通勤偏长"));
    }

    @Test
    void handlesEmptyOrSingleStopSafely() {
        assertTrue(provider.estimateDays("杭州", null).isEmpty());

        DayStopsInput singleStop = new DayStopsInput(1, List.of(
                new StopInput("西湖", "杭州市西湖区", "sightseeing")
        ));
        DayTransportPlan plan = provider.estimateDays("杭州", List.of(singleStop)).get(0);
        assertTrue(plan.segments().isEmpty());
        assertEquals(0.0, plan.totalDistanceKm());
        assertEquals(0, plan.totalDurationMinutes());
        assertEquals(0, plan.totalTransitCost());
        assertNull(plan.routeWarning());
    }
}
