package com.fileshare.repository;

import com.fileshare.domain.FileChunk;
import com.fileshare.domain.enums.ChunkStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileChunkRepository extends JpaRepository<FileChunk, UUID> {

    List<FileChunk> findByFileUploadIdOrderByChunkNumber(UUID fileUploadId);

    Optional<FileChunk> findByFileUploadIdAndChunkNumber(UUID fileUploadId, Integer chunkNumber);

    long countByFileUploadIdAndChunkStatus(UUID fileUploadId, ChunkStatus status);

    long countByFileUploadId(UUID fileUploadId);

    @Query("SELECT fc FROM FileChunk fc WHERE fc.fileUpload.id = :uploadId AND fc.chunkStatus <> 'UPLOADED' ORDER BY fc.chunkNumber")
    List<FileChunk> findIncompleteChunks(@Param("uploadId") UUID uploadId);

    boolean existsByFileUploadIdAndChunkStatusNot(UUID fileUploadId, ChunkStatus status);
}
