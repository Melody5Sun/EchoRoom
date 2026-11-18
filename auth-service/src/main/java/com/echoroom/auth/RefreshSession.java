package com.echoroom.auth;

import java.time.Instant;

public record RefreshSession(long userId, Instant expiresAt, String role) {
}
