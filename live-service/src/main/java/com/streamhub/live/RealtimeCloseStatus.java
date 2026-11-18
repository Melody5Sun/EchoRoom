package com.streamhub.live;

import org.springframework.web.socket.CloseStatus;

final class RealtimeCloseStatus {
    // Gateway 4.2.4 rewrites 1013 to 1002 but forwards application-private codes.
    static final CloseStatus RETRYABLE = new CloseStatus(4001, "Retry later");
    private RealtimeCloseStatus() { }
}
