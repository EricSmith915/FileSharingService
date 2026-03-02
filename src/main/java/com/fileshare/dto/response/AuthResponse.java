package com.fileshare.dto.response;

public record AuthResponse(
        String token,
        String username,
        String tokenType
) {
    public AuthResponse(String token, String username) {
        this(token, username, "Bearer");
    }
}
