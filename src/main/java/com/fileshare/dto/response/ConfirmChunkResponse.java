package com.fileshare.dto.response;

import com.fileshare.domain.enums.ChunkStatus;

public record ConfirmChunkResponse(
        Integer chunkNumber,
        ChunkStatus chunkStatus,
        String etag,
        Integer uploadedChunks,
        Integer totalChunks
) {}
