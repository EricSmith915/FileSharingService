package com.fileshare.dto.response;

import com.fileshare.domain.enums.ChunkStatus;
import com.fileshare.domain.enums.UploadStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record FileStatusResponse(
        UUID uploadId,
        String filename,
        String contentType,
        Long totalSize,
        Integer totalChunks,
        Long chunkSize,
        UploadStatus uploadStatus,
        Integer uploadedChunks,
        List<ChunkInfo> chunks,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {
    public record ChunkInfo(
            Integer chunkNumber,
            ChunkStatus chunkStatus,
            Long chunkSize
    ) {}
}
