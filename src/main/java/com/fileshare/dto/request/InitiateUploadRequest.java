package com.fileshare.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record InitiateUploadRequest(
        @NotBlank String filename,
        String contentType,
        @NotNull @Positive Long totalSize,
        // Optional: if null, the service will use the default chunk size from config
        @Min(5_242_880) Long chunkSize
) {}
