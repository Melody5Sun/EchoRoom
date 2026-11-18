package com.streamhub.live;

import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.*;

class ChatAdmissionTest {
    @Test void chatIsLimitedBeforeMuteProfileOrHistoryQueries() throws Exception {
        var rooms=mock(LiveRoomRepository.class); var messages=mock(ChatMessageRepository.class);
        var users=mock(UserServiceClient.class); var mute=mock(RoomMuteRepository.class); var words=mock(SensitiveWordService.class);
        var guard=mock(TrafficGuard.class); var registry=mock(RoomSessionRegistry.class);
        when(guard.enter("chat",1,2)).thenThrow(new TrafficGuard.Limited(100));
        var handler=new RoomWebSocketHandler(new ObjectMapper(),rooms,messages,mock(OnlinePresenceService.class),registry,
            mock(RoomBroadcastService.class),users,words,mute,guard,75000);
        var session=mock(WebSocketSession.class); when(session.getAttributes()).thenReturn(Map.of("roomId",1L,"userId",2L));
        handler.handleTextMessage(session,new TextMessage("{\"type\":\"CHAT\",\"clientMessageId\":\"c\",\"content\":\"hello\"}"));
        verifyNoInteractions(rooms,messages,users,mute,words);
        verify(registry).send(eq(session),contains("RATE_LIMITED"));
    }
}
