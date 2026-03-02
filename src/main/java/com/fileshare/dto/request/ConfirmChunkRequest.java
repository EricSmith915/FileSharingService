package com.fileshare.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ConfirmChunkRequest(
        // The ETag returned by S3/DO Spaces after the PUT of the chunk part
        @NotBlank String etag
) {}
