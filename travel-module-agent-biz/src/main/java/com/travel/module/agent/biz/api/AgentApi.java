package com.travel.module.agent.biz.api;

import com.travel.module.agent.biz.api.dto.*;
import com.travel.module.agent.biz.application.service.AgentApplicationService;
import com.travel.common.core.result.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Agent API控制器
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AgentApi {

    private final AgentApplicationService agentService;

    /**
     * 创建新会话
     */
    @PostMapping("/sessions")
    public ApiResult<SessionResponse> createSession(@Valid @RequestBody CreateSessionRequest request, HttpServletRequest http) {
        String raw = (String) http.getAttribute("authenticatedUserId");
        Long userId = raw == null ? null : Long.valueOf(raw);
        return ApiResult.success(agentService.createSession(request, userId));
    }

    /**
     * 发送消息
     */
    @PostMapping("/messages")
    public ApiResult<MessageResponse> sendMessage(@Valid @RequestBody SendMessageRequest request) {
        return ApiResult.success(agentService.sendMessage(request));
    }

    /**
     * 处理工具调用结果
     */
    @PostMapping("/tool-result")
    public ApiResult<MessageResponse> handleToolResult(
            @RequestParam String sessionId,
            @RequestParam String toolCallId,
            @RequestParam String toolName,
            @RequestBody Object result) {
        return ApiResult.success(agentService.handleToolResult(sessionId, toolCallId, toolName, result));
    }

    /**
     * 获取会话消息历史
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResult<List<MessageResponse>> getMessages(@PathVariable String sessionId) {
        return ApiResult.success(agentService.getMessages(sessionId));
    }

    /**
     * 获取用户的所有会话
     */
    @GetMapping("/sessions")
    public ApiResult<List<SessionResponse>> getUserSessions(
            @RequestParam(required = false, defaultValue = "1") Long userId) {
        return ApiResult.success(agentService.getUserSessions(userId));
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResult<Void> deleteSession(@PathVariable String sessionId) {
        agentService.deleteSession(sessionId);
        return ApiResult.success();
    }
}
