package com.echoroom.auth;

public record UserAccount(long id, String username, String passwordHash, String role, String status) {
}
