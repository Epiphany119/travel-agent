package com.travel.module.agent.biz.application.service;

import com.travel.module.agent.biz.api.dto.*;
import com.travel.module.agent.biz.domain.entity.ChatMessage;
import com.travel.module.agent.biz.domain.entity.ChatSession;
import com.travel.module.agent.biz.domain.entity.TravelPreference;
import com.travel.module.agent.biz.domain.repository.ChatSessionRepository;
import com.travel.module.agent.biz.domain.service.AgentCoreService;
import com.travel.common.core.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent应用服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentApplicationService {

    private final ChatSessionRepository sessionRepository;
    private final AgentCoreService agentCoreService;

    /**
     * 创建新会话
     */
    public SessionResponse createSession(CreateSessionRequest request, Long authenticatedUserId) {
        if (authenticatedUserId == null) throw new IllegalArgumentException("缺少认证用户身份");
        String sessionId = IdGenerator.generateSessionId();

        ChatSession session = new ChatSession();
        session.setSessionId(sessionId);
        session.setUserId(authenticatedUserId);
        session.setTitle("去" + request.getDestination() + "旅行规划");
        session.setMessages(new ArrayList<>());
        session.setStatus("active");
        session.setCreatedAt(LocalDateTime.now());
        session.setLastActiveAt(LocalDateTime.now());

        // 设置旅行偏好
        TravelPreference preference = TravelPreference.builder()
                .destination(request.getDestination())
                .startDate(request.getStartDate() != null ? LocalDate.parse(request.getStartDate()) : null)
                .endDate(request.getEndDate() != null ? LocalDate.parse(request.getEndDate()) : null)
                .budgetLevel(request.getBudgetLevel())
                .travelType(request.getTravelType())
                .travelers(request.getTravelers())
                .build();
        session.setPreference(preference);

        sessionRepository.save(session);

        return SessionResponse.builder()
                .sessionId(sessionId)
                .title(session.getTitle())
                .status(session.getStatus())
                .createdAt(session.getCreatedAt().toString())
                .build();
    }

    /**
     * 发送消息并获取响应
     */
    public MessageResponse sendMessage(SendMessageRequest request) {
        ChatSession session = sessionRepository.findBySessionId(request.getSessionId());
        if (session == null) {
            throw new RuntimeException("会话不存在");
        }

        String response = agentCoreService.processMessage(session, request.getContent(), Map.of());
        sessionRepository.save(session);

        List<ChatMessage> messages = session.getMessages();
        ChatMessage lastMsg = messages.get(messages.size() - 1);

        return MessageResponse.builder()
                .messageId(lastMsg.getMessageId())
                .role(lastMsg.getRole())
                .content(lastMsg.getContent())
                .needsToolCall(false)
                .build();
    }

    /**
     * 处理工具调用结果
     */
    public MessageResponse handleToolResult(String sessionId, String toolCallId, 
                                            String toolName, Object result) {
        ChatSession session = sessionRepository.findBySessionId(sessionId);
        if (session == null) {
            throw new RuntimeException("会话不存在");
        }

        String response = agentCoreService.processToolResult(session, toolCallId, toolName, result);
        sessionRepository.save(session);

        return MessageResponse.builder()
                .messageId(IdGenerator.generateMessageId())
                .role("assistant")
                .content(response)
                .build();
    }

    /**
     * 获取会话历史
     */
    public List<MessageResponse> getMessages(String sessionId) {
        ChatSession session = sessionRepository.findBySessionId(sessionId);
        if (session == null) {
            return new ArrayList<>();
        }

        return session.getMessages().stream()
                .map(msg -> MessageResponse.builder()
                        .messageId(msg.getMessageId())
                        .role(msg.getRole())
                        .content(msg.getContent())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 获取用户的所有会话
     */
    public List<SessionResponse> getUserSessions(Long userId) {
        return sessionRepository.findByUserId(userId).stream()
                .map(session -> SessionResponse.builder()
                        .sessionId(session.getSessionId())
                        .title(session.getTitle())
                        .status(session.getStatus())
                        .lastMessage(session.getMessages().isEmpty() ? "" : 
                                session.getMessages().get(session.getMessages().size() - 1).getContent())
                        .createdAt(session.getCreatedAt().toString())
                        .lastActiveAt(session.getLastActiveAt().toString())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 删除会话
     */
    public void deleteSession(String sessionId) {
        sessionRepository.deleteBySessionId(sessionId);
    }
}
