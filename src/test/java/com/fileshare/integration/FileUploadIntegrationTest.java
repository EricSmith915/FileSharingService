package com.fileshare.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileshare.domain.enums.ChunkStatus;
import com.fileshare.domain.enums.UploadStatus;
import com.fileshare.dto.request.ConfirmChunkRequest;
import com.fileshare.dto.request.InitiateUploadRequest;
import com.fileshare.dto.request.LoginRequest;
import com.fileshare.dto.request.RegisterRequest;
import com.fileshare.dto.response.*;
import com.fileshare.repository.FileChunkRepository;
import com.fileshare.repository.FileUploadRepository;
import com.fileshare.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration test using a real PostgreSQL container via Testcontainers.
 * The DO Spaces {@link StorageService} is mocked so no real S3 calls are made.
 *
 * Tests the complete multipart upload lifecycle:
 *   register → login → initiate → get presigned URL → confirm chunk → complete
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class FileUploadIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fileshare_test")
            .withUsername("fileshare")
            .withPassword("fileshare_secret");

    @DynamicPropertySource
    static void overrideDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired FileUploadRepository fileUploadRepository;
    @Autowired FileChunkRepository fileChunkRepository;

    // Mock StorageService so tests do not require a real DO Spaces bucket
    @MockBean StorageService storageService;

    @BeforeEach
    void configureMockStorage() {
        when(storageService.initiateMultipartUpload(anyString(), anyString()))
                .thenReturn("mock-s3-upload-id");
        when(storageService.generatePresignedPartUrl(anyString(), anyString(), anyInt()))
                .thenReturn("https://mock-do-spaces.example.com/presigned?part=1");
        doNothing().when(storageService).completeMultipartUpload(anyString(), anyString(), any());
        when(storageService.generatePresignedGetUrl(anyString(), anyLong()))
                .thenReturn("https://mock-do-spaces.example.com/download/file.bin");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String registerAndLogin(String username) throws Exception {
        RegisterRequest reg = new RegisterRequest(username, username + "@test.com", "password123");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(username, "password123"))))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(
                loginResult.getResponse().getContentAsString(), AuthResponse.class);
        return "Bearer " + auth.token();
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void fullMultipartUploadLifecycle() throws Exception {
        String authHeader = registerAndLogin("uploaduser_" + UUID.randomUUID().toString().substring(0, 8));

        // 1. Initiate upload (20 MB → 2 chunks of 10 MB each)
        InitiateUploadRequest initiateRequest = new InitiateUploadRequest(
                "dataset.csv", "text/csv", 20_000_000L, null);

        MvcResult initiateResult = mockMvc.perform(post("/api/v1/files/upload")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initiateRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadStatus").value("INITIATED"))
                .andExpect(jsonPath("$.totalChunks").value(2))
                .andReturn();

        InitiateUploadResponse initiated = objectMapper.readValue(
                initiateResult.getResponse().getContentAsString(), InitiateUploadResponse.class);
        UUID uploadId = initiated.uploadId();
        assertThat(uploadId).isNotNull();

        // 2. Get presigned URL for chunk 1
        mockMvc.perform(get("/api/v1/files/{uploadId}/chunks/1/presigned-url", uploadId)
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presignedUrl").isNotEmpty())
                .andExpect(jsonPath("$.chunkNumber").value(1));

        // 3. Confirm chunk 1 uploaded (client did PUT to the presigned URL)
        mockMvc.perform(post("/api/v1/files/{uploadId}/chunks/1/confirm", uploadId)
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ConfirmChunkRequest("\"etag-chunk-1\""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkStatus").value("UPLOADED"));

        // 4. Confirm chunk 2 uploaded
        mockMvc.perform(post("/api/v1/files/{uploadId}/chunks/2/confirm", uploadId)
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ConfirmChunkRequest("\"etag-chunk-2\""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkStatus").value("UPLOADED"))
                .andExpect(jsonPath("$.uploadedChunks").value(2))
                .andExpect(jsonPath("$.totalChunks").value(2));

        // 5. Complete the upload
        mockMvc.perform(post("/api/v1/files/{uploadId}/complete", uploadId)
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadStatus").value("COMPLETED"));

        // 6. Verify DB state
        var savedUpload = fileUploadRepository.findById(uploadId).orElseThrow();
        assertThat(savedUpload.getUploadStatus()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(savedUpload.getCompletedAt()).isNotNull();

        List<com.fileshare.domain.FileChunk> savedChunks =
                fileChunkRepository.findByFileUploadIdOrderByChunkNumber(uploadId);
        assertThat(savedChunks).hasSize(2)
                .allMatch(c -> c.getChunkStatus() == ChunkStatus.UPLOADED);

        // 7. Generate a signed download link
        MvcResult linkResult = mockMvc.perform(
                        post("/api/v1/files/{uploadId}/download-link", uploadId)
                                .header("Authorization", authHeader)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ttlSeconds\": 3600}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();

        DownloadLinkResponse linkResponse = objectMapper.readValue(
                linkResult.getResponse().getContentAsString(), DownloadLinkResponse.class);

        // 8. Use the signed link to download (extracts token from URL)
        String token = linkResponse.downloadUrl()
                .substring(linkResponse.downloadUrl().indexOf("token=") + 6);

        mockMvc.perform(get("/api/v1/download").param("token", token))
                .andExpect(status().isFound()) // 302 redirect
                .andExpect(header().string("Location",
                        "https://mock-do-spaces.example.com/download/file.bin"));

        // 9. Check audit trail
        mockMvc.perform(get("/api/v1/files/{uploadId}/audit", uploadId)
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void completeUpload_withMissingChunks_returns409() throws Exception {
        String authHeader = registerAndLogin("partialuser_" + UUID.randomUUID().toString().substring(0, 8));

        InitiateUploadRequest req = new InitiateUploadRequest(
                "partial.bin", null, 20_000_000L, null);

        MvcResult result = mockMvc.perform(post("/api/v1/files/upload")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID uploadId = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                InitiateUploadResponse.class).uploadId();

        // Confirm only chunk 1, leave chunk 2 as NOT_UPLOADED
        mockMvc.perform(post("/api/v1/files/{uploadId}/chunks/1/confirm", uploadId)
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ConfirmChunkRequest("\"etag-1\""))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/files/{uploadId}/complete", uploadId)
                        .header("Authorization", authHeader))
                .andExpect(status().isConflict());
    }

    @Test
    void download_withInvalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/download").param("token", "invalid.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void download_withExpiredToken_returns410() throws Exception {
        // Generate a token that is already expired
        String authHeader = registerAndLogin("expireduser_" + UUID.randomUUID().toString().substring(0, 8));

        InitiateUploadRequest req = new InitiateUploadRequest(
                "expiry.bin", null, 10_485_760L, null);
        MvcResult result = mockMvc.perform(post("/api/v1/files/upload")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID uploadId = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                InitiateUploadResponse.class).uploadId();

        // Confirm the single chunk and complete
        mockMvc.perform(post("/api/v1/files/{uploadId}/chunks/1/confirm", uploadId)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmChunkRequest("\"e1\""))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/files/{uploadId}/complete", uploadId)
                .header("Authorization", authHeader))
                .andExpect(status().isOk());

        // Request a link with TTL of 1 second, then wait for it to expire
        MvcResult linkResult = mockMvc.perform(
                        post("/api/v1/files/{uploadId}/download-link", uploadId)
                                .header("Authorization", authHeader)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ttlSeconds\": 1}"))
                .andExpect(status().isOk())
                .andReturn();
        DownloadLinkResponse linkResponse = objectMapper.readValue(
                linkResult.getResponse().getContentAsString(), DownloadLinkResponse.class);
        String token = linkResponse.downloadUrl()
                .substring(linkResponse.downloadUrl().indexOf("token=") + 6);

        // Sleep just over 1 second so the token expires
        Thread.sleep(1100);

        mockMvc.perform(get("/api/v1/download").param("token", token))
                .andExpect(status().isGone());
    }
}
