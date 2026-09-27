package com.travel.a2a.controller;

import com.travel.a2a.persistence.AiPlanRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** 计划查询和出行生命周期接口。 */
@RestController
@RequestMapping("/a2a/plans")
@RequiredArgsConstructor
public class AiPlanController {

    private final AiPlanRepository planRepository;

    @GetMapping("/{planId}")
    public Map<String, Object> getPlan(@PathVariable String planId, HttpServletRequest request) {
        String ownerId = currentUserId(request);
        AiPlanRepository.PlanRecord plan = planRepository.findByPlanId(planId).orElse(null);
        if (plan == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "计划不存在");
        if (!ownerId.equals(plan.ownerId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该计划");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("plan", plan);
        response.put("version", planRepository.getVersion(planId, plan.currentVersion(), ownerId));
        return response;
    }

    @PostMapping("/{planId}/status")
    public Map<String, Object> transition(@PathVariable String planId,
                                          @RequestBody Map<String, String> body,
                                          HttpServletRequest request) {
        String ownerId = currentUserId(request);
        String target = body == null ? null : body.get("status");
        if (target == null || target.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        AiPlanRepository.PlanRecord plan = planRepository.transition(planId, ownerId, target.trim().toUpperCase());
        if (plan == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "计划不存在");
        return Map.of("planId", plan.planId(), "status", plan.lifecycleStatus(), "version", plan.currentVersion());
    }

    private String currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute("authenticatedUserId");
        if (value == null || value.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return value.toString();
    }
}
