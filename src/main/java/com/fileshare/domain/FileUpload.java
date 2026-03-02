package com.fileshare.domain;

import com.fileshare.domain.enums.UploadStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "file_uploads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // The JWT subject (username) of the file owner
    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "total_size", nullable = false)
    private Long totalSize;

    @Column(name = "total_chunks", nullable = false)
    private Integer totalChunks;

    @Column(name = "chunk_size", nullable = false)
    private Long chunkSize;

    // Object key in DO Spaces, e.g. uploads/{ownerId}/{uuid}/{filename}
    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    // Multipart upload ID returned by DO Spaces / S3
    @Column(name = "s3_upload_id")
    private String s3UploadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", nullable = false)
    private UploadStatus uploadStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @OneToMany(mappedBy = "fileUpload", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<FileChunk> chunks = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
        if (uploadStatus == null) {
            uploadStatus = UploadStatus.INITIATED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
