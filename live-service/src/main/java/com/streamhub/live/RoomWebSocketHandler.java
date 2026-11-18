package com.streamhub.live;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {


    private final ObjectMapper objectMapper;
    private final LiveRoomRepository liveRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final OnlinePresenceService onlinePresenceService;
    private final RoomSessionRegistry roomSessionRegistry;
    private final RoomBroadcastService roomBroadcastService;
    private final UserServiceClient userServiceClient;
    private final SensitiveWordService sensitiveWordService;
    private final RoomMuteRepository roomMuteRepository;
    private final long sessionTimeoutMillis;
    private final TrafficGuard trafficGuard;

    public RoomWebSocketHandler(
            ObjectMapper objectMapper,
            LiveRoomRepository liveRoomRepository,
            ChatMessageRepository chatMessageRepository,
            OnlinePresenceService onlinePresenceService,
            RoomSessionRegistry roomSessionRegistry,
            RoomBroadcastService roomBroadcastService,
            UserServiceClient userServiceClient,
            SensitiveWordService sensitiveWordService,
            RoomMuteRepository roomMuteRepository, TrafficGuard trafficGuard,
            @Value("${streamhub.realtime.session-timeout-ms:75000}") long sessionTimeoutMillis) {
        this.objectMapper = objectMapper;
        this.trafficGuard = trafficGuard;
        this.liveRoomRepository = liveRoomRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.onlinePresenceService = onlinePresenceService;
        this.roomSessionRegistry = roomSessionRegistry;
        this.roomBroadcastService = roomBroadcastService;
        this.userServiceClient = userServiceClient;
        this.sensitiveWordService = sensitiveWordService;
        this.roomMuteRepository = roomMuteRepository;
        this.sessionTimeoutMillis = Math.max(1_000, sessionTimeoutMillis);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Map<String, String> query = queryParameters(session);
        Long roomId = parsePositiveLong(query.get("roomId"));
        Long userId = parsePositiveLong(query.get("userId"));
        if (roomId == null || userId == null) {
            reject(session, RealtimeAdmissionMetrics.Reason.INVALID_REQUEST, false);
            return;
        }
        try {
            if (liveRoomRepository.findCachedById(roomId).isEmpty()) {
                reject(session, RealtimeAdmissionMetrics.Reason.ROOM_MISSING, false); return;
            }
        } catch (RuntimeException unavailable) {
            reject(session, RealtimeAdmissionMetrics.Reason.ROOM_UNAVAILABLE, true); return;
        }
        try {
            var response = userServiceClient.getProfile(userId);
            if (response == null || !response.success() || response.data() == null) {
                reject(session, RealtimeAdmissionMetrics.Reason.USER_UNAVAILABLE, true); return;
            }
            if (!"ACTIVE".equals(response.data().status())) {
                reject(session, RealtimeAdmissionMetrics.Reason.USER_INACTIVE, false); return;
            }
        } catch (feign.FeignException unavailable) {
            boolean missing = unavailable.status() == 404;
            reject(session, missing ? RealtimeAdmissionMetrics.Reason.USER_INACTIVE : RealtimeAdmissionMetrics.Reason.USER_UNAVAILABLE, !missing);
            return;
        } catch (RuntimeException unavailable) {
            reject(session, RealtimeAdmissionMetrics.Reason.USER_UNAVAILABLE, true); return;
        }

        session.getAttributes().put("roomId", roomId);
        session.getAttributes().put("userId", userId);
        if (!roomSessionRegistry.add(roomId, userId, session)) {
            return;
        }
        onlinePresenceService.join(roomId, userId, session.getId());
        send(session, Map.of(
                "type", "CONNECTED",
                "roomId", roomId,
                "userId", userId,
                "serverTime", Instant.now()));
    }

    private void reject(WebSocketSession session, RealtimeAdmissionMetrics.Reason reason, boolean unavailable) {
        roomSessionRegistry.rejectAdmission(session, reason,
                unavailable ? RealtimeCloseStatus.RETRYABLE : CloseStatus.POLICY_VIOLATION);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        Long roomId = attributeAsLong(session, "roomId");
        Long userId = attributeAsLong(session, "userId");
        if (roomId == null || userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        ChatCommand command = objectMapper.readValue(message.getPayload(), ChatCommand.class);
        String type = StringUtils.hasText(command.type()) ? command.type().trim().toUpperCase() : "CHAT";
        if ("HEARTBEAT".equals(type)) {
            roomSessionRegistry.heartbeat(roomId, session);
            onlinePresenceService.heartbeat(roomId, userId, session.getId());
            send(session, Map.of("type", "HEARTBEAT_ACK", "serverTime", Instant.now()));
            return;
        }
        if (!"CHAT".equals(type)) {
            sendError(session, "只支持 CHAT 和 HEARTBEAT 消息");
            return;
        }
        if (!StringUtils.hasText(command.clientMessageId()) || command.clientMessageId().length() > 64) {
            sendError(session, "clientMessageId 必填且长度不能超过 64");
            return;
        }
        if (!StringUtils.hasText(command.content()) || command.content().length() > 512) {
            sendError(session, "弹幕内容不能为空且长度不能超过 512");
            return;
        }

        TrafficGuard.Lease lease;
        try { lease = trafficGuard.enter("chat", roomId, userId); }
        catch (com.streamhub.common.api.BusinessException ex) {
            long wait = ex instanceof TrafficGuard.Limited limited ? limited.retryAfterMs : 1000;
            send(session, Map.of("type", "ERROR", "code", ex.errorCode() == com.streamhub.common.api.ErrorCode.RATE_LIMITED ? "RATE_LIMITED" : "UNAVAILABLE",
                "message", ex.getMessage(), "retryAfterMs", wait));
            return;
        }
        try (lease) {
        if (roomMuteRepository.isMuted(roomId, userId, Instant.now())) {
            send(session, Map.of("type", "ERROR", "code", "ROOM_MUTED", "message", "你已被本房间禁言"));
            return;
        }
        var matchedWord = sensitiveWordService.matchedWord(command.content().trim());
        if (matchedWord.isPresent()) {
            sensitiveWordService.logBlocked(roomId, userId, command.content().trim(), matchedWord.get());
            send(session, Map.of(
                    "type", "ERROR",
                    "code", "CONTENT_BLOCKED",
                    "message", "弹幕包含敏感内容，请修改后重试"));
            return;
        }
        UserProfileSnapshot profile = activeUserProfile(userId);
        if (profile == null) {
            sendError(session, "账号已被封禁");
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        ChatMessage saved = chatMessageRepository.save(
                roomId,
                userId,
                command.clientMessageId(),
                command.content().trim());
        // A persistence acknowledgement is independent of Pub/Sub delivery and enables loss auditing.
        send(session, Map.of("type", "CHAT_ACK", "id", saved.id(), "roomId", roomId,
                "clientMessageId", saved.clientMessageId()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "CHAT");
        payload.put("id", saved.id());
        payload.put("roomId", saved.roomId());
        payload.put("userId", saved.userId());
        payload.put("nickname", StringUtils.hasText(profile.nickname()) ? profile.nickname() : profile.username());
        payload.put("avatarUrl", profile.avatarUrl());
        payload.put("clientMessageId", saved.clientMessageId());
        payload.put("content", saved.content());
        payload.put("createdAt", saved.createdAt());
        roomBroadcastService.publish(roomId, payload);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long roomId = attributeAsLong(session, "roomId");
        Long userId = attributeAsLong(session, "userId");
        if (roomId != null) {
            roomSessionRegistry.remove(roomId, session);
        }

    }

    @Scheduled(fixedDelayString = "${streamhub.realtime.session-scan-interval-ms:15000}")
    public void closeExpiredSessions() {
        long cutoff = System.currentTimeMillis() - sessionTimeoutMillis;
        roomSessionRegistry.removeExpired(cutoff);
    }

    public void broadcastEvent(long roomId, Map<String, Object> payload) {
        roomBroadcastService.publish(roomId, payload);
    }

    private void send(WebSocketSession session, Map<String, Object> payload) throws IOException {
        roomSessionRegistry.send(session, objectMapper.writeValueAsString(payload));
    }

    private void sendError(WebSocketSession session, String message) throws IOException {
        send(session, Map.of("type", "ERROR", "message", message));
    }

    private Map<String, String> queryParameters(WebSocketSession session) {
        if (session.getUri() == null) {
            return Map.of();
        }
        return UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().toSingleValueMap();
    }

    private Long parsePositiveLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Long attributeAsLong(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        return value instanceof Long ? (Long) value : null;
    }

    private UserProfileSnapshot activeUserProfile(long userId) {
        try {
            var response = userServiceClient.getProfile(userId);
            if (response != null && response.success() && response.data() != null
                    && "ACTIVE".equals(response.data().status())) {
                return response.data();
            }
        } catch (RuntimeException exception) {
            return null;
        }
        return null;
    }

    public record ChatCommand(String type, String clientMessageId, String content) {
    }
}
