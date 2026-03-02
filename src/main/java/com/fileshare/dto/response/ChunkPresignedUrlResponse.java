package com.fileshare.dto.response;

import com.fileshare.domain.enums.ChunkStatus;

import java.time.OffsetDateTime;

public record ChunkPresignedUrlResponse(
        Integer chunkNumber,
        String presignedUrl,
        OffsetDateTime expiresAt,
        ChunkStatus chunkStatus
) {}
