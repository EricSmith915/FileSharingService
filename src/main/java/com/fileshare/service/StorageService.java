package com.fileshare.service;

import com.fileshare.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

import java.time.Duration;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final AppProperties appProperties;

    /**
     * Initiates a multipart upload on DO Spaces and returns the UploadId.
     *
     * @param s3Key       The object key, e.g. uploads/{ownerId}/{uuid}/{filename}
     * @param contentType The MIME type of the file
     * @return The S3 multipart UploadId
     */
    public String initiateMultipartUpload(String s3Key, String contentType) {
        String bucket = appProperties.getDoSpaces().getBucket();

        CreateMultipartUploadRequest.Builder requestBuilder = CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(s3Key);

        if (contentType != null && !contentType.isBlank()) {
            requestBuilder.contentType(contentType);
        }

        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(requestBuilder.build());
        log.info("Initiated multipart upload: key={} uploadId={}", s3Key, response.uploadId());
        return response.uploadId();
    }

    /**
     * Generates a pre-signed URL for a client to PUT a single part directly to DO Spaces.
     * The TTL is set from config (default 60 min) to give clients enough time to upload.
     *
     * @param s3Key      The object key
     * @param uploadId   The multipart upload ID
     * @param partNumber 1-based part number
     * @return The pre-signed PUT URL string
     */
    public String generatePresignedPartUrl(String s3Key, String uploadId, int partNumber) {
        String bucket = appProperties.getDoSpaces().getBucket();
        int ttlMinutes = appProperties.getDoSpaces().getPresignedUrlTtlMinutes();

        UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(ttlMinutes))
                .uploadPartRequest(r -> r
                        .bucket(bucket)
                        .key(s3Key)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                )
                .build();

        PresignedUploadPartRequest presignedRequest = s3Presigner.presignUploadPart(presignRequest);
        return presignedRequest.url().toString();
    }

    /**
     * Completes a multipart upload using all collected parts.
     * Parts MUST be in ascending order by part number.
     *
     * @param s3Key    The object key
     * @param uploadId The multipart upload ID
     * @param parts    Sorted list of completed parts (partNumber + etag)
     */
    public void completeMultipartUpload(String s3Key, String uploadId,
                                        List<CompletedPart> parts) {
        String bucket = appProperties.getDoSpaces().getBucket();

        CompleteMultipartUploadRequest request = CompleteMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .uploadId(uploadId)
                .multipartUpload(m -> m.parts(parts))
                .build();

        s3Client.completeMultipartUpload(request);
        log.info("Completed multipart upload: key={}", s3Key);
    }

    /**
     * Aborts a multipart upload to free up partial data in DO Spaces.
     * Should be called when an upload is cancelled or permanently failed.
     *
     * @param s3Key    The object key
     * @param uploadId The multipart upload ID
     */
    public void abortMultipartUpload(String s3Key, String uploadId) {
        String bucket = appProperties.getDoSpaces().getBucket();
        try {
            AbortMultipartUploadRequest request = AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .uploadId(uploadId)
                    .build();
            s3Client.abortMultipartUpload(request);
            log.info("Aborted multipart upload: key={} uploadId={}", s3Key, uploadId);
        } catch (Exception e) {
            log.warn("Failed to abort multipart upload: key={} uploadId={}: {}", s3Key, uploadId, e.getMessage());
        }
    }

    /**
     * Generates a short-lived pre-signed GET URL for downloading a file from DO Spaces.
     * Used when redirecting clients after validating HMAC download tokens.
     *
     * @param s3Key      The object key
     * @param ttlSeconds Seconds until the URL expires
     * @return The pre-signed GET URL string
     */
    public String generatePresignedGetUrl(String s3Key, long ttlSeconds) {
        String bucket = appProperties.getDoSpaces().getBucket();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(ttlSeconds))
                .getObjectRequest(r -> r
                        .bucket(bucket)
                        .key(s3Key)
                )
                .build();

        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
        return presignedRequest.url().toString();
    }
}
