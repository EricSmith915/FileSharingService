package com.fileshare.dto.response;

import java.time.OffsetDateTime;

public record DownloadLinkResponse(
        String downloadUrl,
        OffsetDateTime expiresAt
) {}
