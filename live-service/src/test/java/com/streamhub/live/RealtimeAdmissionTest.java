package com.streamhub.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamhub.common.api.ApiResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.socket.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.streamhub.live.RealtimeAdmissionMetrics.Reason.*;

class RealtimeAdmissionTest {
    private final LiveRoomRepository rooms = mock(LiveRoomRepository.class);
    private final UserServiceClient users = mock(UserServiceClient.class);
    private final RoomSessionRegistry registry = mock(RoomSessionRegistry.class);
    private final RoomWebSocketHandler handler = new RoomWebSocketHandler(new ObjectMapper(), rooms,
            mock(ChatMessageRepository.class), mock(OnlinePresenceService.class), registry,
            mock(RoomBroadcastService.class), users, mock(SensitiveWordService.class),
            mock(RoomMuteRepository.class), mock(TrafficGuard.class), 75000);
    private WebSocketSession session(String query) {
        var session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?" + query));
        when(session.getAttributes()).thenReturn(new HashMap<>());
        return session;
    }
    @Test void malformedIdentityDoesNotQueryDependencies() throws Exception {
        var socket = session("roomId=no&userId=7");
        handler.afterConnectionEstablished(socket);
        verifyNoInteractions(rooms, users);
        verify(registry).rejectAdmission(socket, INVALID_REQUEST, CloseStatus.POLICY_VIOLATION);
    }
    @Test void userDependencyFailureIsRetryableAndDoesNotRegister() throws Exception {
        when(rooms.findCachedById(1)).thenReturn(Optional.of(mock(LiveRoom.class)));
        when(users.getProfile(7)).thenThrow(new IllegalStateException("unavailable"));
        var socket = session("roomId=1&userId=7");
        handler.afterConnectionEstablished(socket);
        verify(registry).rejectAdmission(socket, USER_UNAVAILABLE, RealtimeCloseStatus.RETRYABLE);
        verify(registry, never()).add(anyLong(), anyLong(), any());
    }
    @Test void authoritativeInactiveUserStillCannotJoin() throws Exception {
        when(rooms.findCachedById(1)).thenReturn(Optional.of(mock(LiveRoom.class)));
        when(users.getProfile(7)).thenReturn(ApiResponse.success(new UserProfileSnapshot(7,"u","u",null,"USER","BANNED",0)));
        var socket = session("roomId=1&userId=7");
        handler.afterConnectionEstablished(socket);
        verify(registry).rejectAdmission(socket, USER_INACTIVE, CloseStatus.POLICY_VIOLATION);
        verify(registry, never()).add(anyLong(), anyLong(), any());
    }
    @Test void roomDependencyFailureIsDistinctFromMissingRoom() throws Exception {
        when(rooms.findCachedById(1)).thenThrow(new IllegalStateException("database"));
        var socket = session("roomId=1&userId=7");
        handler.afterConnectionEstablished(socket);
        verify(registry).rejectAdmission(socket, ROOM_UNAVAILABLE, RealtimeCloseStatus.RETRYABLE);
        verifyNoInteractions(users);
    }
    @Test void subscriptionFailureHasItsOwnBoundedReasonAndNoConnectionLeak() {
        var meter = new SimpleMeterRegistry();
        var sender = mock(SessionSender.class);
        var subscriptions = mock(RoomSubscriptions.class);
        doThrow(new IllegalStateException()).when(subscriptions).retain(1);
        var real = new RoomSessionRegistry(10, 10, sender, subscriptions, null, new RealtimeAdmissionMetrics(meter));
        var socket = session("roomId=1&userId=7");
        try {
            assertThat(real.add(1,7,socket)).isFalse();
            verify(sender).closeBeforeRegistration(socket, RealtimeCloseStatus.RETRYABLE);
            assertThat(meter.get("streamhub.realtime.admission.rejected").tag("reason","subscription_unavailable").counter().count()).isEqualTo(1);
            verify(sender, never()).register(any(), any());
            verify(subscriptions, never()).release(anyLong());
        } finally { real.shutdown(); }
    }
    @Test void preRegistrationCloseUsesIndependentCloserAndPreservesCode() throws Exception {
        var sender = new SessionSender(new MockEnvironment(), new SimpleMeterRegistry());
        var socket = session("roomId=1&userId=7");
        try {
            sender.closeBeforeRegistration(socket, CloseStatus.POLICY_VIOLATION);
            verify(socket, timeout(1000)).close(CloseStatus.POLICY_VIOLATION);
        } finally { sender.shutdown(); }
    }
}
