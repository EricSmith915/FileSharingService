package com.fileshare.dto.response;

import java.time.OffsetDateTime;

public record ErrorResponse(
        int status,
        String error,
        String message,
        OffsetDateTime timestamp
) {
    public ErrorResponse(int status, String error, String message) {
        this(status, error, message, OffsetDateTime.now());
    }
}
