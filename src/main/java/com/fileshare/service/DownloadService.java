package com.fileshare.service;

import com.fileshare.domain.FileUpload;
import com.fileshare.domain.enums.AuditEventType;
import com.fileshare.domain.enums.UploadStatus;
import com.fileshare.exception.FileNotFoundException;
import com.fileshare.exception.UploadStateException;
import com.fileshare.repository.FileUploadRepository;
import com.fileshare.util.HmacSignatureUtil;
import com.fileshare.util.HmacSignatureUtil.TokenClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class DownloadService {

    // Short-lived S3 presigned URL TTL after HMAC token validation (seconds)
    private static final long S3_REDIRECT_TTL_SECONDS = 60L;

    private final HmacSignatureUtil hmacSignatureUtil;
    private final StorageService storageService;
    private final FileUploadRepository fileUploadRepository;
    private final AuditService auditService;

    /**
     * Validates the HMAC-signed token, verifies the file is accessible,
     * records a FILE_ACCESSED audit event, and returns a short-lived S3 presigned GET URL
     * for the client to be redirected to.
     *
     * @param token     The HMAC-signed download token from the query parameter
     * @param ipAddress The requesting client's IP address
     * @param userAgent The requesting client's User-Agent
     * @return A short-lived S3 presigned URL for direct download
     */
    @Transactional(readOnly = true)
    public String resolveDownloadRedirect(String token, String ipAddress, String userAgent) {
        // Validates signature and expiry; throws InvalidTokenException or TokenExpiredException
        TokenClaims claims = hmacSignatureUtil.validateToken(token);

        FileUpload fileUpload = fileUploadRepository.findById(claims.fileUploadId())
                .orElseThrow(() -> new FileNotFoundException(
                        "File not found for the provided token"));

        if (fileUpload.getUploadStatus() != UploadStatus.COMPLETED) {
            throw new UploadStateException("File is not available for download");
        }

        // Verify s3Key in token matches actual file to prevent token substitution
        if (!fileUpload.getS3Key().equals(claims.s3Key())) {
            log.warn("Token s3Key mismatch for fileId={}", claims.fileUploadId());
            throw new com.fileshare.exception.InvalidTokenException("Token does not match file");
        }

        // Generate a short-lived presigned GET URL and redirect client directly to DO Spaces
        String redirectUrl = storageService.generatePresignedGetUrl(
                fileUpload.getS3Key(), S3_REDIRECT_TTL_SECONDS);

        auditService.record(fileUpload, AuditEventType.FILE_ACCESSED, null, ipAddress, userAgent,
                Map.of("filename", fileUpload.getFilename()));

        return redirectUrl;
    }
}
