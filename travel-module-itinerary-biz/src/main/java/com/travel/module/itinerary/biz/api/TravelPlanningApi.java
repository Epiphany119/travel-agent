package com.travel.module.itinerary.biz.api;

import com.travel.common.core.result.ApiResult;
import com.travel.module.itinerary.biz.api.dto.GenerateItineraryRequest;
import com.travel.module.itinerary.biz.api.dto.TravelPlanResponse;
import com.travel.module.itinerary.biz.application.service.TravelPlanningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/travel-plans")
@RequiredArgsConstructor
public class TravelPlanningApi {
    private final TravelPlanningService planningService;
    @PostMapping("/generate")
    public ApiResult<TravelPlanResponse> generate(@Valid @RequestBody GenerateItineraryRequest request) {
        return ApiResult.success(planningService.generate(request));
    }
}
