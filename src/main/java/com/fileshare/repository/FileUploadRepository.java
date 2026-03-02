package com.fileshare.repository;

import com.fileshare.domain.FileUpload;
import com.fileshare.domain.enums.UploadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FileUploadRepository extends JpaRepository<FileUpload, UUID> {

    Page<FileUpload> findByOwnerIdOrderByCreatedAtDesc(String ownerId, Pageable pageable);

    Page<FileUpload> findByOwnerIdAndUploadStatusOrderByCreatedAtDesc(
            String ownerId, UploadStatus uploadStatus, Pageable pageable);

    Optional<FileUpload> findByIdAndOwnerId(UUID id, String ownerId);

    @Query("SELECT f FROM FileUpload f WHERE f.id = :id AND f.ownerId = :ownerId AND f.uploadStatus = :status")
    Optional<FileUpload> findByIdAndOwnerIdAndStatus(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId,
            @Param("status") UploadStatus status);

    boolean existsByIdAndOwnerId(UUID id, String ownerId);
}
