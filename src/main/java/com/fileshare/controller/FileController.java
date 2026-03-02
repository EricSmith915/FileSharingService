package com.fileshare.controller;

import com.fileshare.domain.enums.UploadStatus;
import com.fileshare.dto.request.ConfirmChunkRequest;
import com.fileshare.dto.request.GenerateDownloadLinkRequest;
import com.fileshare.dto.request.InitiateUploadRequest;
import com.fileshare.dto.response.*;
import com.fileshare.repository.AuditEventRepository;
import com.fileshare.service.FileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final AuditEventRepository auditEventRepository;

    /**
     * Initiates a multipart upload session.
     * Returns upload metadata. The client then calls the presigned-url endpoint per chunk.
     */
    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public InitiateUploadResponse initiateUpload(
            @Valid @RequestBody InitiateUploadRequest request,
            @AuthenticationPrincipal String ownerId,
            HttpServletRequest httpRequest) {
        return fileService.initiateUpload(
                request, ownerId,
                getClientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));
    }

    /**
     * Returns a pre-signed PUT URL for a specific chunk.
     * The client uploads the raw bytes directly to DO Spaces using this URL.
     */
    @GetMapping("/{uploadId}/chunks/{chunkNumber}/presigned-url")
    public ChunkPresignedUrlResponse getPresignedUrl(
            @PathVariable UUID uploadId,
            @PathVariable int chunkNumber,
            @AuthenticationPrincipal String ownerId,
            HttpServletRequest httpRequest) {
        return fileService.getPresignedUrlForChunk(
                uploadId, chunkNumber, ownerId,
                getClientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));
    }

    /**
     * Confirms that a chunk was successfully uploaded to DO Spaces.
     * The client provides the ETag from the S3 response header.
     */
    @PostMapping("/{uploadId}/chunks/{chunkNumber}/confirm")
    public ConfirmChunkResponse confirmChunk(
            @PathVariable UUID uploadId,
            @PathVariable int chunkNumber,
            @Valid @RequestBody ConfirmChunkRequest request,
            @AuthenticationPrincipal String ownerId,
            HttpServletRequest httpRequest) {
        return fileService.confirmChunkUploaded(
                uploadId, chunkNumber, request, ownerId,
                getClientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));
    }

    /**
     * Completes the multipart upload by assembling all parts in DO Spaces.
     * All chunks must be confirmed before calling this endpoint.
     */
    @PostMapping("/{uploadId}/complete")
    public FileMetadataResponse completeUpload(
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal String ownerId,
            HttpServletRequest httpRequest) {
        return fileService.completeUpload(
                uploadId, ownerId,
                getClientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));
    }

    /**
     * Generates an HMAC-signed download URL for a completed file.
     * The URL points back to this service, which validates and redirects to DO Spaces.
     */
    @PostMapping("/{uploadId}/download-link")
    public DownloadLinkResponse generateDownloadLink(
            @PathVariable UUID uploadId,
            @RequestBody(required = false) GenerateDownloadLinkRequest request,
            @AuthenticationPrincipal String ownerId,
            HttpServletRequest httpRequest) {
        String baseUrl = httpRequest.getScheme() + "://" + httpRequest.getServerName()
                + (httpRequest.getServerPort() != 80 && httpRequest.getServerPort() != 443
                        ? ":" + httpRequest.getServerPort() : "");
        return fileService.generateDownloadLink(
                uploadId, request, ownerId, baseUrl,
                getClientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));
    }

    /**
     * Lists files owned by the authenticated user, with optional status filter.
     */
    @GetMapping
    public Page<FileMetadataResponse> listFiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UploadStatus status,
            @AuthenticationPrincipal String ownerId) {
        return fileService.listFiles(ownerId, page, Math.min(size, 100), status);
    }

    /**
     * Returns detailed status for a single file including per-chunk status.
     */
    @GetMapping("/{uploadId}")
    public FileStatusResponse getFileStatus(
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal String ownerId) {
        return fileService.getFileStatus(uploadId, ownerId);
    }

    /**
     * Returns paginated audit events for a file owned by the authenticated user.
     */
    @GetMapping("/{uploadId}/audit")
    public org.springframework.data.domain.Page<AuditEventResponse> getAuditEvents(
            @PathVariable UUID uploadId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal String ownerId) {
        // Verify ownership first
        fileService.getFileStatus(uploadId, ownerId);

        return auditEventRepository
                .findByFileUploadIdOrderByCreatedAtDesc(
                        uploadId, PageRequest.of(page, Math.min(size, 100)))
                .map(e -> new AuditEventResponse(
                        e.getId(),
                        e.getEventType(),
                        e.getActorId(),
                        e.getIpAddress(),
                        e.getMetadata(),
                        e.getCreatedAt()
                ));
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
