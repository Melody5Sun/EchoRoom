package com.echoroom.live;

import java.time.Instant;

public record SensitiveWord(long id, String word, String status, Instant createdAt) {
}
