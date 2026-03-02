package com.fileshare.dto.response;

import com.fileshare.domain.enums.UploadStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FileMetadataResponse(
        UUID uploadId,
        String filename,
        String contentType,
        Long totalSize,
        Integer totalChunks,
        Long chunkSize,
        UploadStatus uploadStatus,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {}
