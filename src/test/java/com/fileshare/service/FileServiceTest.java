package com.fileshare.service;

import com.fileshare.config.AppProperties;
import com.fileshare.domain.FileChunk;
import com.fileshare.domain.FileUpload;
import com.fileshare.domain.enums.ChunkStatus;
import com.fileshare.domain.enums.UploadStatus;
import com.fileshare.dto.request.ConfirmChunkRequest;
import com.fileshare.dto.request.InitiateUploadRequest;
import com.fileshare.dto.response.ConfirmChunkResponse;
import com.fileshare.dto.response.FileStatusResponse;
import com.fileshare.dto.response.InitiateUploadResponse;
import com.fileshare.exception.FileNotFoundException;
import com.fileshare.exception.UploadStateException;
import com.fileshare.repository.AuditEventRepository;
import com.fileshare.repository.FileChunkRepository;
import com.fileshare.repository.FileUploadRepository;
import com.fileshare.util.HmacSignatureUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    @Mock FileUploadRepository fileUploadRepository;
    @Mock FileChunkRepository fileChunkRepository;
    @Mock AuditEventRepository auditEventRepository;
    @Mock StorageService storageService;
    @Mock AuditService auditService;
    @Mock HmacSignatureUtil hmacSignatureUtil;
    @Mock AppProperties appProperties;

    @InjectMocks FileService fileService;

    private static final String OWNER_ID = "testuser";
    private static final String IP = "127.0.0.1";
    private static final String UA = "JUnit";

    @BeforeEach
    void configureMocks() {
        AppProperties.Upload upload = new AppProperties.Upload();
        AppProperties.DoSpaces spaces = new AppProperties.DoSpaces();
        AppProperties.Hmac hmac = new AppProperties.Hmac();

        when(appProperties.getUpload()).thenReturn(upload);
        when(appProperties.getDoSpaces()).thenReturn(spaces);
        when(appProperties.getHmac()).thenReturn(hmac);
        when(storageService.initiateMultipartUpload(anyString(), anyString()))
                .thenReturn("s3-upload-id-123");
        when(fileUploadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fileChunkRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // -----------------------------------------------------------------------
    // initiateUpload
    // -----------------------------------------------------------------------

    @Test
    void initiateUpload_validRequest_createsUploadAndChunks() {
        InitiateUploadRequest request = new InitiateUploadRequest(
                "video.mp4", "video/mp4", 30_000_000L, null);

        InitiateUploadResponse response = fileService.initiateUpload(request, OWNER_ID, IP, UA);

        assertThat(response.filename()).isEqualTo("video.mp4");
        assertThat(response.uploadStatus()).isEqualTo(UploadStatus.INITIATED);
        // 30 MB / 10 MB default = 3 chunks
        assertThat(response.totalChunks()).isEqualTo(3);
        assertThat(response.chunkSize()).isEqualTo(10_485_760L);

        verify(storageService).initiateMultipartUpload(anyString(), eq("video/mp4"));
        verify(fileUploadRepository).save(any(FileUpload.class));
        verify(fileChunkRepository).saveAll(argThat((List<FileChunk> chunks) -> chunks.size() == 3));
    }

    @Test
    void initiateUpload_fileTooLarge_throwsIllegalArgumentException() {
        long tooBig = 53_687_091_201L; // 50 GB + 1 byte
        InitiateUploadRequest request = new InitiateUploadRequest("big.zip", null, tooBig, null);

        assertThatThrownBy(() -> fileService.initiateUpload(request, OWNER_ID, IP, UA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum");
    }

    @Test
    void initiateUpload_chunkSizeBelowMinimum_throwsIllegalArgumentException() {
        InitiateUploadRequest request = new InitiateUploadRequest(
                "file.bin", null, 20_000_000L, 1_000_000L); // 1 MB chunk — below 5 MB min

        assertThatThrownBy(() -> fileService.initiateUpload(request, OWNER_ID, IP, UA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least");
    }

    @Test
    void initiateUpload_lastChunkSmallerThanDefault() {
        // 25 MB / 10 MB = 2 full chunks + 1 partial chunk of 5 MB
        InitiateUploadRequest request = new InitiateUploadRequest(
                "data.bin", null, 25_000_000L, null);

        InitiateUploadResponse response = fileService.initiateUpload(request, OWNER_ID, IP, UA);

        assertThat(response.totalChunks()).isEqualTo(3);
    }

    // -----------------------------------------------------------------------
    // confirmChunkUploaded
    // -----------------------------------------------------------------------

    @Test
    void confirmChunk_validEtag_updatesChunkStatus() {
        UUID uploadId = UUID.randomUUID();
        FileUpload fileUpload = buildFileUpload(uploadId, UploadStatus.IN_PROGRESS, 3);
        FileChunk chunk = buildChunk(fileUpload, 1, ChunkStatus.UPLOADING);

        when(fileUploadRepository.findByIdAndOwnerId(uploadId, OWNER_ID))
                .thenReturn(Optional.of(fileUpload));
        when(fileChunkRepository.findByFileUploadIdAndChunkNumber(uploadId, 1))
                .thenReturn(Optional.of(chunk));
        when(fileChunkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fileChunkRepository.countByFileUploadIdAndChunkStatus(uploadId, ChunkStatus.UPLOADED))
                .thenReturn(1L);

        ConfirmChunkResponse response = fileService.confirmChunkUploaded(
                uploadId, 1, new ConfirmChunkRequest("\"etag-abc\""), OWNER_ID, IP, UA);

        assertThat(response.chunkStatus()).isEqualTo(ChunkStatus.UPLOADED);
        assertThat(response.etag()).isEqualTo("\"etag-abc\"");
        assertThat(chunk.getEtag()).isEqualTo("\"etag-abc\"");
    }

    @Test
    void confirmChunk_uploadAlreadyCompleted_throwsUploadStateException() {
        UUID uploadId = UUID.randomUUID();
        FileUpload fileUpload = buildFileUpload(uploadId, UploadStatus.COMPLETED, 2);

        when(fileUploadRepository.findByIdAndOwnerId(uploadId, OWNER_ID))
                .thenReturn(Optional.of(fileUpload));

        assertThatThrownBy(() -> fileService.confirmChunkUploaded(
                uploadId, 1, new ConfirmChunkRequest("\"etag\""), OWNER_ID, IP, UA))
                .isInstanceOf(UploadStateException.class);
    }

    @Test
    void confirmChunk_unknownFile_throwsFileNotFoundException() {
        UUID uploadId = UUID.randomUUID();
        when(fileUploadRepository.findByIdAndOwnerId(uploadId, OWNER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileService.confirmChunkUploaded(
                uploadId, 1, new ConfirmChunkRequest("\"etag\""), OWNER_ID, IP, UA))
                .isInstanceOf(FileNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // completeUpload
    // -----------------------------------------------------------------------

    @Test
    void completeUpload_allChunksUploaded_completesSuccessfully() {
        UUID uploadId = UUID.randomUUID();
        FileUpload fileUpload = buildFileUpload(uploadId, UploadStatus.IN_PROGRESS, 2);
        List<FileChunk> chunks = List.of(
                buildChunk(fileUpload, 1, ChunkStatus.UPLOADED, "\"etag-1\""),
                buildChunk(fileUpload, 2, ChunkStatus.UPLOADED, "\"etag-2\"")
        );

        when(fileUploadRepository.findByIdAndOwnerId(uploadId, OWNER_ID))
                .thenReturn(Optional.of(fileUpload));
        when(fileChunkRepository.findByFileUploadIdOrderByChunkNumber(uploadId))
                .thenReturn(chunks);
        when(fileUploadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(storageService).completeMultipartUpload(anyString(), anyString(), any());

        var response = fileService.completeUpload(uploadId, OWNER_ID, IP, UA);

        assertThat(response.uploadStatus()).isEqualTo(UploadStatus.COMPLETED);
        verify(storageService).completeMultipartUpload(
                eq(fileUpload.getS3Key()), eq(fileUpload.getS3UploadId()), argThat(p -> p.size() == 2));
    }

    @Test
    void completeUpload_incompleteChunks_throwsUploadStateException() {
        UUID uploadId = UUID.randomUUID();
        FileUpload fileUpload = buildFileUpload(uploadId, UploadStatus.IN_PROGRESS, 2);
        List<FileChunk> chunks = List.of(
                buildChunk(fileUpload, 1, ChunkStatus.UPLOADED, "\"etag-1\""),
                buildChunk(fileUpload, 2, ChunkStatus.NOT_UPLOADED, null)
        );

        when(fileUploadRepository.findByIdAndOwnerId(uploadId, OWNER_ID))
                .thenReturn(Optional.of(fileUpload));
        when(fileChunkRepository.findByFileUploadIdOrderByChunkNumber(uploadId))
                .thenReturn(chunks);

        assertThatThrownBy(() -> fileService.completeUpload(uploadId, OWNER_ID, IP, UA))
                .isInstanceOf(UploadStateException.class)
                .hasMessageContaining("not yet uploaded");
    }

    // -----------------------------------------------------------------------
    // getFileStatus
    // -----------------------------------------------------------------------

    @Test
    void getFileStatus_returnsCorrectChunkSummary() {
        UUID uploadId = UUID.randomUUID();
        FileUpload fileUpload = buildFileUpload(uploadId, UploadStatus.IN_PROGRESS, 3);
        List<FileChunk> chunks = List.of(
                buildChunk(fileUpload, 1, ChunkStatus.UPLOADED, "\"e1\""),
                buildChunk(fileUpload, 2, ChunkStatus.UPLOADING, null),
                buildChunk(fileUpload, 3, ChunkStatus.NOT_UPLOADED, null)
        );

        when(fileUploadRepository.findByIdAndOwnerId(uploadId, OWNER_ID))
                .thenReturn(Optional.of(fileUpload));
        when(fileChunkRepository.findByFileUploadIdOrderByChunkNumber(uploadId))
                .thenReturn(chunks);

        FileStatusResponse status = fileService.getFileStatus(uploadId, OWNER_ID);

        assertThat(status.uploadedChunks()).isEqualTo(1);
        assertThat(status.totalChunks()).isEqualTo(3);
        assertThat(status.chunks()).hasSize(3);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private FileUpload buildFileUpload(UUID id, UploadStatus status, int totalChunks) {
        FileUpload f = new FileUpload();
        f.setId(id);
        f.setOwnerId(OWNER_ID);
        f.setFilename("test-file.bin");
        f.setContentType("application/octet-stream");
        f.setTotalSize(totalChunks * 10_485_760L);
        f.setTotalChunks(totalChunks);
        f.setChunkSize(10_485_760L);
        f.setS3Key("uploads/" + OWNER_ID + "/" + id + "/test-file.bin");
        f.setS3UploadId("s3-upload-id");
        f.setUploadStatus(status);
        f.setCreatedAt(OffsetDateTime.now());
        f.setUpdatedAt(OffsetDateTime.now());
        return f;
    }

    private FileChunk buildChunk(FileUpload upload, int number, ChunkStatus status) {
        return buildChunk(upload, number, status, null);
    }

    private FileChunk buildChunk(FileUpload upload, int number, ChunkStatus status, String etag) {
        FileChunk c = new FileChunk();
        c.setId(UUID.randomUUID());
        c.setFileUpload(upload);
        c.setChunkNumber(number);
        c.setChunkSize(10_485_760L);
        c.setChunkStatus(status);
        c.setEtag(etag);
        c.setCreatedAt(OffsetDateTime.now());
        c.setUpdatedAt(OffsetDateTime.now());
        return c;
    }
}
