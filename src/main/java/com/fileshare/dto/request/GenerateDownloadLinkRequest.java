package com.fileshare.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

public record GenerateDownloadLinkRequest(
        // TTL in seconds; defaults to app.hmac.default-link-ttl-seconds if null
        @Positive @Max(604800) Long ttlSeconds   // max 7 days
) {}
