package com.fileshare.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileshare.config.SecurityConfig;
import com.fileshare.domain.enums.UploadStatus;
import com.fileshare.dto.request.ConfirmChunkRequest;
import com.fileshare.dto.request.InitiateUploadRequest;
import com.fileshare.dto.response.*;
import com.fileshare.exception.FileNotFoundException;
import com.fileshare.exception.GlobalExceptionHandler;
import com.fileshare.exception.UploadStateException;
import com.fileshare.repository.AuditEventRepository;
import com.fileshare.security.JwtAuthenticationEntryPoint;
import com.fileshare.security.JwtAuthenticationFilter;
import com.fileshare.service.FileService;
import com.fileshare.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FileController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class FileControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean FileService fileService;
    @MockBean AuditEventRepository auditEventRepository;

    // SecurityConfig constructor-injects these; mock them so the web slice starts
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    @MockBean JwtService jwtService;

    // -----------------------------------------------------------------------
    // POST /api/v1/files/upload
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = "alice")
    void initiateUpload_validRequest_returns201() throws Exception {
        UUID uploadId = UUID.randomUUID();
        InitiateUploadRequest request = new InitiateUploadRequest(
                "report.pdf", "application/pdf", 20_000_000L, null);

        InitiateUploadResponse response = new InitiateUploadResponse(
                uploadId, "report.pdf", "application/pdf", 20_000_000L,
                2, 10_485_760L, UploadStatus.INITIATED, OffsetDateTime.now());

        when(fileService.initiateUpload(any(), eq("alice"), any(), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/files/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadId").value(uploadId.toString()))
                .andExpect(jsonPath("$.uploadStatus").value("INITIATED"))
                .andExpect(jsonPath("$.totalChunks").value(2));
    }

    @Test
    @WithMockUser
    void initiateUpload_missingFilename_returns400() throws Exception {
        InitiateUploadRequest request = new InitiateUploadRequest(
                "", "application/pdf", 20_000_000L, null);

        mockMvc.perform(post("/api/v1/files/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void initiateUpload_noAuth_returns401() throws Exception {
        InitiateUploadRequest request = new InitiateUploadRequest(
                "file.bin", null, 1_000_000L, null);

        mockMvc.perform(post("/api/v1/files/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/files/{uploadId}/chunks/{n}/presigned-url
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = "alice")
    void getPresignedUrl_validChunk_returns200() throws Exception {
        UUID uploadId = UUID.randomUUID();
        ChunkPresignedUrlResponse response = new ChunkPresignedUrlResponse(
                1, "https://bucket.nyc3.digitaloceanspaces.com/presigned-url",
                OffsetDateTime.now().plusHours(1),
                com.fileshare.domain.enums.ChunkStatus.UPLOADING);

        when(fileService.getPresignedUrlForChunk(eq(uploadId), eq(1), eq("alice"), any(), any()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/files/{uploadId}/chunks/1/presigned-url", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkNumber").value(1))
                .andExpect(jsonPath("$.presignedUrl").isNotEmpty());
    }

    @Test
    @WithMockUser
    void getPresignedUrl_fileNotOwned_returns404() throws Exception {
        UUID uploadId = UUID.randomUUID();
        when(fileService.getPresignedUrlForChunk(any(), anyInt(), any(), any(), any()))
                .thenThrow(new FileNotFoundException("File not found: " + uploadId));

        mockMvc.perform(get("/api/v1/files/{uploadId}/chunks/1/presigned-url", uploadId))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // POST /api/v1/files/{uploadId}/chunks/{n}/confirm
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = "alice")
    void confirmChunk_validEtag_returns200() throws Exception {
        UUID uploadId = UUID.randomUUID();
        ConfirmChunkRequest request = new ConfirmChunkRequest("\"d8e8fca2dc0f896fd7cb\"");
        ConfirmChunkResponse response = new ConfirmChunkResponse(
                1, com.fileshare.domain.enums.ChunkStatus.UPLOADED,
                "\"d8e8fca2dc0f896fd7cb\"", 1, 3);

        when(fileService.confirmChunkUploaded(eq(uploadId), eq(1), any(), eq("alice"), any(), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/files/{uploadId}/chunks/1/confirm", uploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkStatus").value("UPLOADED"));
    }

    // -----------------------------------------------------------------------
    // POST /api/v1/files/{uploadId}/complete
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = "alice")
    void completeUpload_allChunksDone_returns200() throws Exception {
        UUID uploadId = UUID.randomUUID();
        FileMetadataResponse response = new FileMetadataResponse(
                uploadId, "report.pdf", "application/pdf", 20_000_000L,
                2, 10_485_760L, UploadStatus.COMPLETED,
                OffsetDateTime.now().minusMinutes(5), OffsetDateTime.now());

        when(fileService.completeUpload(eq(uploadId), eq("alice"), any(), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/files/{uploadId}/complete", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadStatus").value("COMPLETED"));
    }

    @Test
    @WithMockUser
    void completeUpload_chunksNotReady_returns409() throws Exception {
        UUID uploadId = UUID.randomUUID();
        when(fileService.completeUpload(any(), any(), any(), any()))
                .thenThrow(new UploadStateException("Chunks not yet uploaded: [2, 3]"));

        mockMvc.perform(post("/api/v1/files/{uploadId}/complete", uploadId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Chunks not yet uploaded: [2, 3]"));
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/files
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = "alice")
    void listFiles_returnsPagedResults() throws Exception {
        FileMetadataResponse item = new FileMetadataResponse(
                UUID.randomUUID(), "archive.tar.gz", "application/gzip",
                50_000_000L, 5, 10_485_760L, UploadStatus.COMPLETED,
                OffsetDateTime.now().minusHours(1), OffsetDateTime.now().minusMinutes(30));

        when(fileService.listFiles(eq("alice"), eq(0), eq(20), isNull()))
                .thenReturn(new PageImpl<>(List.of(item)));

        mockMvc.perform(get("/api/v1/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].filename").value("archive.tar.gz"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/files/{uploadId}
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = "alice")
    void getFileStatus_existingFile_returnsChunkBreakdown() throws Exception {
        UUID uploadId = UUID.randomUUID();
        FileStatusResponse response = new FileStatusResponse(
                uploadId, "video.mp4", "video/mp4", 30_000_000L, 3, 10_485_760L,
                UploadStatus.IN_PROGRESS, 1,
                List.of(
                        new FileStatusResponse.ChunkInfo(1, com.fileshare.domain.enums.ChunkStatus.UPLOADED, 10_485_760L),
                        new FileStatusResponse.ChunkInfo(2, com.fileshare.domain.enums.ChunkStatus.UPLOADING, 10_485_760L),
                        new FileStatusResponse.ChunkInfo(3, com.fileshare.domain.enums.ChunkStatus.NOT_UPLOADED, 9_028_480L)
                ),
                OffsetDateTime.now(), null);

        when(fileService.getFileStatus(uploadId, "alice")).thenReturn(response);

        mockMvc.perform(get("/api/v1/files/{uploadId}", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadedChunks").value(1))
                .andExpect(jsonPath("$.chunks").isArray())
                .andExpect(jsonPath("$.chunks.length()").value(3));
    }
}
