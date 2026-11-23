package com.echoroom.live;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SrsHookControllerTest {
    private final LiveRoomRepository rooms = mock(LiveRoomRepository.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SrsHookController(new LiveRoomService(
            rooms, mock(OnlinePresenceService.class), mock(MediaProperties.class),
            mock(UserServiceClient.class), mock(RoomFavoriteRepository.class)))).build();

    private org.springframework.test.web.servlet.ResultActions publish(String app, String stream, String param)
            throws Exception {
        String body = "{\"action\":\"on_publish\",\"app\":\"" + app + "\",\"stream\":\"" + stream + "\""
                + (param == null ? "" : ",\"param\":\"" + param + "\"") + "}";
        return mvc.perform(post("/internal/srs/on-publish").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void allowsTheIssuedStreamKeyOfALiveRoom() throws Exception {
        when(rooms.isLiveWithStreamKeyHash(eq(3L), anyString())).thenAnswer(inv ->
                inv.getArgument(1).equals(sha256("good-key")));

        publish("live", "3", "?vhost=x&streamKey=good-key").andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        publish("live", "3", "?streamKey=wrong").andExpect(status().isForbidden());
    }

    @Test
    void rejectsMissingKeyOtherAppsAndNonNumericStreamsWithoutQueryingTheDatabase() throws Exception {
        publish("live", "3", null).andExpect(status().isForbidden());
        publish("live", "3", "?streamKey=").andExpect(status().isForbidden());
        publish("other", "3", "?streamKey=good-key").andExpect(status().isForbidden());
        publish("live", "abc", "?streamKey=good-key").andExpect(status().isForbidden());
        verifyNoInteractions(rooms);
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
