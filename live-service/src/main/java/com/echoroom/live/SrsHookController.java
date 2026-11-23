package com.echoroom.live;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** SRS http_hooks callbacks. Internal path, not routed by the API gateway. */
@RestController
public class SrsHookController {
    private static final Logger log = LoggerFactory.getLogger(SrsHookController.class);

    private final LiveRoomService liveRoomService;

    public SrsHookController(LiveRoomService liveRoomService) {
        this.liveRoomService = liveRoomService;
    }

    // SRS allows the publish only on HTTP 200 with {"code":0}; anything else rejects it.
    @PostMapping("/internal/srs/on-publish")
    public ResponseEntity<Map<String, Integer>> onPublish(@RequestBody Map<String, Object> body) {
        String app = String.valueOf(body.get("app"));
        String stream = String.valueOf(body.get("stream"));
        boolean allowed = liveRoomService.canPublish(app, stream, body.get("param") instanceof String param ? param : null);
        if (!allowed) {
            log.warn("拒绝推流 app={} stream={} ip={}", app, stream, body.get("ip"));
            return ResponseEntity.status(403).body(Map.of("code", 403));
        }
        return ResponseEntity.ok(Map.of("code", 0));
    }
}
