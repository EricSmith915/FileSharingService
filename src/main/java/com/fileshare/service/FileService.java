package com.fileshare.service;

import com.fileshare.config.AppProperties;
import com.fileshare.domain.FileChunk;
import com.fileshare.domain.FileUpload;
import com.fileshare.domain.enums.AuditEventType;
import com.fileshare.domain.enums.ChunkStatus;
import com.fileshare.domain.enums.UploadStatus;
import com.fileshare.dto.request.ConfirmChunkRequest;
import com.fileshare.dto.request.GenerateDownloadLinkRequest;
import com.fileshare.dto.request.InitiateUploadRequest;
import com.fileshare.dto.response.*;
import com.fileshare.exception.FileNotFoundException;
import com.fileshare.exception.UnauthorizedAccessException;
import com.fileshare.exception.UploadStateException;
import com.fileshare.repository.AuditEventRepository;
import com.fileshare.repository.FileChunkRepository;
import com.fileshare.repository.FileUploadRepository;
import com.fileshare.util.HmacSignatureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class FileService {

    private final FileUploadRepository fileUploadRepository;
    private final FileChunkRepository fileChunkRepository;
    private final AuditEventRepository auditEventRepository;
    private final StorageService storageService;
    private final AuditService auditService;
    private final HmacSignatureUtil hmacSignatureUtil;
    private final AppProperties appProperties;

    /**
     * Initiates a multipart upload in DO Spaces and creates tracking records in the database.
     * Returns upload metadata; the client then requests per-chunk presigned URLs separately.
     */
    @Transactional
    public InitiateUploadResponse initiateUpload(InitiateUploadRequest request,
                                                 String ownerId,
                                                 String ipAddress,
                                                 String userAgent) {
        long maxFileSize = appProperties.getUpload().getMaxFileSizeBytes();
        if (request.totalSize() > maxFileSize) {
            throw new IllegalArgumentException(
                    "File size exceeds maximum allowed size of " + maxFileSize + " bytes");
        }

        long chunkSize = resolveChunkSize(request.chunkSize());
        int totalChunks = (int) Math.ceil((double) request.totalSize() / chunkSize);
        String uploadUuid = UUID.randomUUID().toString();
        String s3Key = buildS3Key(ownerId, uploadUuid, request.filename());

        // Initiate multipart upload in DO Spaces
        String s3UploadId = storageService.initiateMultipartUpload(s3Key, request.contentType());

        // Persist FileUpload record
        FileUpload fileUpload = FileUpload.builder()
                .ownerId(ownerId)
                .filename(request.filename())
                .contentType(request.contentType())
                .totalSize(request.totalSize())
                .totalChunks(totalChunks)
                .chunkSize(chunkSize)
                .s3Key(s3Key)
                .s3UploadId(s3UploadId)
                .uploadStatus(UploadStatus.INITIATED)
                .build();
        fileUploadRepository.save(fileUpload);

        // Create all chunk records in a batch (status=NOT_UPLOADED)
        List<FileChunk> chunks = IntStream.rangeClosed(1, totalChunks)
                .mapToObj(n -> {
                    long size = (n == totalChunks)
                            ? request.totalSize() - (long) (n - 1) * chunkSize
                            : chunkSize;
                    return FileChunk.builder()
                            .fileUpload(fileUpload)
                            .chunkNumber(n)
                            .chunkSize(size)
                            .chunkStatus(ChunkStatus.NOT_UPLOADED)
                            .build();
                })
                .collect(Collectors.toList());
        fileChunkRepository.saveAll(chunks);

        // Record audit event asynchronously
        auditService.record(fileUpload, AuditEventType.UPLOAD_INITIATED, ownerId, ipAddress,
                userAgent, Map.of("totalChunks", totalChunks, "totalSize", request.totalSize()));

        return new InitiateUploadResponse(
                fileUpload.getId(),
                fileUpload.getFilename(),
                fileUpload.getContentType(),
                fileUpload.getTotalSize(),
                fileUpload.getTotalChunks(),
                fileUpload.getChunkSize(),
                fileUpload.getUploadStatus(),
                fileUpload.getCreatedAt()
        );
    }

    /**
     * Returns a pre-signed PUT URL for the client to upload a specific chunk directly to DO Spaces.
     * Caches the URL in the DB so subsequent calls return the cached URL if still valid.
     */
    @Transactional
    public ChunkPresignedUrlResponse getPresignedUrlForChunk(UUID uploadId,
                                                             int chunkNumber,
                                                             String ownerId,
                                                             String ipAddress,
                                                             String userAgent) {
        FileUpload fileUpload = requireOwned(uploadId, ownerId);

        if (fileUpload.getUploadStatus() == UploadStatus.COMPLETED
                || fileUpload.getUploadStatus() == UploadStatus.ABORTED) {
            throw new UploadStateException("Upload is already " + fileUpload.getUploadStatus());
        }

        if (chunkNumber < 1 || chunkNumber > fileUpload.getTotalChunks()) {
            throw new IllegalArgumentException(
                    "Chunk number must be between 1 and " + fileUpload.getTotalChunks());
        }

        FileChunk chunk = fileChunkRepository
                .findByFileUploadIdAndChunkNumber(uploadId, chunkNumber)
                .orElseThrow(() -> new FileNotFoundException(
                        "Chunk " + chunkNumber + " not found for upload " + uploadId));

        // Return cached URL if still valid (with 5 min buffer)
        if (chunk.getPresignedUrl() != null
                && chunk.getPresignedUrlExpiresAt() != null
                && chunk.getPresignedUrlExpiresAt().isAfter(OffsetDateTime.now().plusMinutes(5))) {
            return new ChunkPresignedUrlResponse(
                    chunk.getChunkNumber(),
                    chunk.getPresignedUrl(),
                    chunk.getPresignedUrlExpiresAt(),
                    chunk.getChunkStatus()
            );
        }

        // Generate fresh presigned URL
        String presignedUrl = storageService.generatePresignedPartUrl(
                fileUpload.getS3Key(), fileUpload.getS3UploadId(), chunkNumber);

        int ttlMinutes = appProperties.getDoSpaces().getPresignedUrlTtlMinutes();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(ttlMinutes);

        chunk.setPresignedUrl(presignedUrl);
        chunk.setPresignedUrlExpiresAt(expiresAt);
        chunk.setChunkStatus(ChunkStatus.UPLOADING);
        fileChunkRepository.save(chunk);

        auditService.record(fileUpload, AuditEventType.CHUNK_UPLOAD_STARTED, ownerId, ipAddress,
                userAgent, Map.of("chunkNumber", chunkNumber));

        return new ChunkPresignedUrlResponse(chunkNumber, presignedUrl, expiresAt, chunk.getChunkStatus());
    }

    /**
     * Confirms that a chunk has been successfully uploaded to DO Spaces.
     * The client provides the ETag returned by S3, which is required for CompleteMultipartUpload.
     */
    @Transactional
    public ConfirmChunkResponse confirmChunkUploaded(UUID uploadId,
                                                     int chunkNumber,
                                                     ConfirmChunkRequest request,
                                                     String ownerId,
                                                     String ipAddress,
                                                     String userAgent) {
        FileUpload fileUpload = requireOwned(uploadId, ownerId);

        if (fileUpload.getUploadStatus() == UploadStatus.COMPLETED
                || fileUpload.getUploadStatus() == UploadStatus.ABORTED) {
            throw new UploadStateException("Upload is already " + fileUpload.getUploadStatus());
        }

        FileChunk chunk = fileChunkRepository
                .findByFileUploadIdAndChunkNumber(uploadId, chunkNumber)
                .orElseThrow(() -> new FileNotFoundException(
                        "Chunk " + chunkNumber + " not found for upload " + uploadId));

        chunk.setChunkStatus(ChunkStatus.UPLOADED);
        chunk.setEtag(request.etag());
        fileChunkRepository.save(chunk);

        long uploadedCount = fileChunkRepository.countByFileUploadIdAndChunkStatus(
                uploadId, ChunkStatus.UPLOADED);

        // Transition to IN_PROGRESS once at least one chunk is uploaded
        if (fileUpload.getUploadStatus() == UploadStatus.INITIATED) {
            fileUpload.setUploadStatus(UploadStatus.IN_PROGRESS);
            fileUploadRepository.save(fileUpload);
        }

        auditService.record(fileUpload, AuditEventType.CHUNK_UPLOADED, ownerId, ipAddress,
                userAgent, Map.of("chunkNumber", chunkNumber, "etag", request.etag()));

        return new ConfirmChunkResponse(
                chunkNumber,
                ChunkStatus.UPLOADED,
                request.etag(),
                (int) uploadedCount,
                fileUpload.getTotalChunks()
        );
    }

    /**
     * Completes the multipart upload by assembling all parts in DO Spaces.
     * All chunks must be UPLOADED with ETags before this can be called.
     */
    @Transactional
    public FileMetadataResponse completeUpload(UUID uploadId,
                                               String ownerId,
                                               String ipAddress,
                                               String userAgent) {
        FileUpload fileUpload = requireOwned(uploadId, ownerId);

        if (fileUpload.getUploadStatus() == UploadStatus.COMPLETED) {
            throw new UploadStateException("Upload is already completed");
        }
        if (fileUpload.getUploadStatus() == UploadStatus.ABORTED
                || fileUpload.getUploadStatus() == UploadStatus.FAILED) {
            throw new UploadStateException("Upload has been " + fileUpload.getUploadStatus());
        }

        // Verify all chunks are uploaded
        List<FileChunk> chunks = fileChunkRepository
                .findByFileUploadIdOrderByChunkNumber(uploadId);

        List<FileChunk> incomplete = chunks.stream()
                .filter(c -> c.getChunkStatus() != ChunkStatus.UPLOADED)
                .toList();

        if (!incomplete.isEmpty()) {
            List<Integer> incompleteNumbers = incomplete.stream()
                    .map(FileChunk::getChunkNumber).toList();
            throw new UploadStateException(
                    "Chunks not yet uploaded: " + incompleteNumbers);
        }

        // Build sorted CompletedPart list for S3
        List<CompletedPart> parts = chunks.stream()
                .sorted(Comparator.comparingInt(FileChunk::getChunkNumber))
                .map(c -> CompletedPart.builder()
                        .partNumber(c.getChunkNumber())
                        .eTag(c.getEtag())
                        .build())
                .toList();

        try {
            storageService.completeMultipartUpload(
                    fileUpload.getS3Key(), fileUpload.getS3UploadId(), parts);
        } catch (Exception e) {
            fileUpload.setUploadStatus(UploadStatus.FAILED);
            fileUploadRepository.save(fileUpload);
            auditService.record(fileUpload, AuditEventType.UPLOAD_FAILED, ownerId, ipAddress,
                    userAgent, Map.of("error", e.getMessage()));
            throw new UploadStateException("Failed to complete upload in storage: " + e.getMessage());
        }

        fileUpload.setUploadStatus(UploadStatus.COMPLETED);
        fileUpload.setCompletedAt(OffsetDateTime.now());
        fileUploadRepository.save(fileUpload);

        auditService.record(fileUpload, AuditEventType.UPLOAD_COMPLETED, ownerId, ipAddress, userAgent);

        return toMetadataResponse(fileUpload);
    }

    /**
     * Generates a cryptographically signed download URL for a completed file.
     * The URL points back to this service which validates the token and redirects to DO Spaces.
     */
    @Transactional(readOnly = true)
    public DownloadLinkResponse generateDownloadLink(UUID uploadId,
                                                     GenerateDownloadLinkRequest request,
                                                     String ownerId,
                                                     String baseUrl,
                                                     String ipAddress,
                                                     String userAgent) {
        FileUpload fileUpload = fileUploadRepository.findByIdAndOwnerId(uploadId, ownerId)
                .orElseThrow(() -> new FileNotFoundException("File not found: " + uploadId));

        if (fileUpload.getUploadStatus() != UploadStatus.COMPLETED) {
            throw new UploadStateException("File is not yet fully uploaded");
        }

        long ttlSeconds = (request != null && request.ttlSeconds() != null)
                ? request.ttlSeconds()
                : appProperties.getHmac().getDefaultLinkTtlSeconds();

        long expiresEpoch = Instant.now().getEpochSecond() + ttlSeconds;
        String token = hmacSignatureUtil.createToken(uploadId, fileUpload.getS3Key(), expiresEpoch);
        OffsetDateTime expiresAt = Instant.ofEpochSecond(expiresEpoch).atOffset(ZoneOffset.UTC);

        String downloadUrl = baseUrl + "/api/v1/download?token=" + token;

        auditService.record(fileUpload, AuditEventType.DOWNLOAD_LINK_GENERATED, ownerId, ipAddress,
                userAgent, Map.of("expiresAt", expiresAt.toString(), "ttlSeconds", ttlSeconds));

        return new DownloadLinkResponse(downloadUrl, expiresAt);
    }

    /**
     * Returns paginated metadata for all files owned by the requesting user.
     */
    @Transactional(readOnly = true)
    public Page<FileMetadataResponse> listFiles(String ownerId, int page, int size,
                                                UploadStatus status) {
        Pageable pageable = PageRequest.of(page, size);
        Page<FileUpload> uploads = (status != null)
                ? fileUploadRepository.findByOwnerIdAndUploadStatusOrderByCreatedAtDesc(
                        ownerId, status, pageable)
                : fileUploadRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId, pageable);

        return uploads.map(this::toMetadataResponse);
    }

    /**
     * Returns detailed status for a single file including chunk-level information.
     */
    @Transactional(readOnly = true)
    public FileStatusResponse getFileStatus(UUID uploadId, String ownerId) {
        FileUpload fileUpload = requireOwned(uploadId, ownerId);
        List<FileChunk> chunks = fileChunkRepository.findByFileUploadIdOrderByChunkNumber(uploadId);

        long uploadedCount = chunks.stream()
                .filter(c -> c.getChunkStatus() == ChunkStatus.UPLOADED)
                .count();

        List<FileStatusResponse.ChunkInfo> chunkInfos = chunks.stream()
                .map(c -> new FileStatusResponse.ChunkInfo(
                        c.getChunkNumber(), c.getChunkStatus(), c.getChunkSize()))
                .toList();

        return new FileStatusResponse(
                fileUpload.getId(),
                fileUpload.getFilename(),
                fileUpload.getContentType(),
                fileUpload.getTotalSize(),
                fileUpload.getTotalChunks(),
                fileUpload.getChunkSize(),
                fileUpload.getUploadStatus(),
                (int) uploadedCount,
                chunkInfos,
                fileUpload.getCreatedAt(),
                fileUpload.getCompletedAt()
        );
    }

    // --- Helpers ---

    private FileUpload requireOwned(UUID uploadId, String ownerId) {
        return fileUploadRepository.findByIdAndOwnerId(uploadId, ownerId)
                .orElseThrow(() -> new FileNotFoundException("File not found: " + uploadId));
    }

    private long resolveChunkSize(Long requested) {
        long defaultSize = appProperties.getUpload().getDefaultChunkSizeBytes();
        long minSize = appProperties.getUpload().getMinChunkSizeBytes();
        if (requested == null) return defaultSize;
        if (requested < minSize) {
            throw new IllegalArgumentException(
                    "Chunk size must be at least " + minSize + " bytes (S3 minimum)");
        }
        return requested;
    }

    private String buildS3Key(String ownerId, String uploadUuid, String filename) {
        // Sanitize filename to prevent path traversal
        String safeFilename = filename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        return "uploads/" + ownerId + "/" + uploadUuid + "/" + safeFilename;
    }

    private FileMetadataResponse toMetadataResponse(FileUpload f) {
        return new FileMetadataResponse(
                f.getId(), f.getFilename(), f.getContentType(),
                f.getTotalSize(), f.getTotalChunks(), f.getChunkSize(),
                f.getUploadStatus(), f.getCreatedAt(), f.getCompletedAt()
        );
    }
}
