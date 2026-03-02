package com.fileshare.domain;

import com.fileshare.domain.enums.ChunkStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "file_chunks",
    uniqueConstraints = @UniqueConstraint(columnNames = {"file_upload_id", "chunk_number"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_upload_id", nullable = false)
    private FileUpload fileUpload;

    // 1-based chunk index matching the S3 multipart part number
    @Column(name = "chunk_number", nullable = false)
    private Integer chunkNumber;

    @Column(name = "chunk_size")
    private Long chunkSize;

    // ETag returned by S3 after the chunk is uploaded; required for CompleteMultipartUpload
    @Column(name = "etag")
    private String etag;

    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_status", nullable = false)
    private ChunkStatus chunkStatus;

    // Cached pre-signed PUT URL for the client to upload directly to DO Spaces
    @Column(name = "presigned_url", columnDefinition = "TEXT")
    private String presignedUrl;

    @Column(name = "presigned_url_expires_at")
    private OffsetDateTime presignedUrlExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
        if (chunkStatus == null) {
            chunkStatus = ChunkStatus.NOT_UPLOADED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
