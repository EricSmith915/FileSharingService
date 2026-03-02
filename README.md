# Secure File Sharing API

A production-ready REST API for secure file ingestion, storage, and distribution. Built with Java 17 + Spring Boot 3.2, backed by DigitalOcean Spaces for object storage and PostgreSQL for metadata and audit persistence.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Design Decisions](#design-decisions)
- [Data Model](#data-model)
- [API Reference](#api-reference)
- [Security Model](#security-model)
- [How to Run](#how-to-run)
- [Configuration Reference](#configuration-reference)
- [Testing Strategy](#testing-strategy)
- [Project Structure](#project-structure)

---

## Architecture Overview

The service acts as a **coordination layer** — it never touches file bytes directly. All file data flows between the client and DigitalOcean Spaces via pre-signed URLs. This keeps the application tier stateless, lightweight, and horizontally scalable.

```
┌─────────┐     JWT-authenticated requests      ┌─────────────────┐
│  Client │ ─────────────────────────────────► │  Fileshare API  │
│         │                                     │  (Spring Boot)  │
│         │ ◄─────────────────────────────────  │                 │
│         │     presigned URLs + metadata        └────────┬────────┘
│         │                                               │  metadata reads/writes
│         │    raw file data (PUT/GET)           ┌────────▼────────┐
│         │ ══════════════════════════════════►  │  DO Spaces (S3) │
│         │ ◄══════════════════════════════════  │  Object Store   │
└─────────┘                                      └─────────────────┘

                                               ┌──────────────────┐
                                               │   PostgreSQL     │
                                               │  (Metadata +     │
                                               │   Audit Log)     │
                                               └──────────────────┘
```

### Key architectural properties

| Property | How it is achieved |
|---|---|
| **Stateless service** | JWT tokens carry identity; HMAC secrets are env-injected — any replica handles any request |
| **Large file support (0–50 GB)** | S3 multipart upload — clients upload chunks directly to DO Spaces, bypassing the app tier entirely |
| **Restart-safe signed URLs** | HMAC-SHA256 tokens signed with a server secret from environment config; no session state needed |
| **High availability** | No shared in-memory state; run N replicas behind a load balancer without coordination |
| **Audit durability** | Audit events use `ON DELETE SET NULL` — records are preserved even if the parent file is deleted |

---

## Design Decisions

### 1. Pre-signed URL upload (client-to-storage direct transfer)

Instead of streaming file bytes through the application server, the service issues pre-signed `PUT` URLs for each chunk. The client uploads directly to DigitalOcean Spaces. This means:

- The app tier never becomes a bottleneck for large files
- No request timeouts caused by slow uploads
- Network bandwidth cost is not incurred by the application server
- The app tier remains horizontally scalable without shared upload state

### 2. Multipart upload with chunk-level tracking

Files are split into chunks (default 10 MB, minimum 5 MB per S3 specification). Each chunk is tracked independently in the `file_chunks` table with one of three statuses: `NOT_UPLOADED → UPLOADING → UPLOADED`.

Benefits:
- A failed network connection mid-upload does not require restarting from scratch
- The client can query `GET /files/{uploadId}` to find exactly which chunks are missing and resume only those
- Per-chunk ETags are stored and used to assemble the final object via `CompleteMultipartUpload`

**Consistency trade-off:** The `UPLOADING` status is set when the client requests a pre-signed URL, not when S3 confirms receipt (there is no S3 callback). A chunk stuck in `UPLOADING` means the client disconnected after requesting the URL but before or during upload. A background cleanup job (not in scope for this service) should reset stuck chunks after a configurable timeout.

### 3. HMAC-SHA256 signed download URLs

Download links are signed with `HMAC-SHA256` using a server secret injected via environment variable. The token encodes:

```
payload  = "{fileUploadId}|{s3Key}|{expireEpochSeconds}|v1"
signature = HMAC-SHA256(payload, HMAC_SECRET)
token    = base64url(payload) + "." + base64url(signature)
```

Key properties:
- **Restart-safe** — the secret lives in config, not in memory; any replica can verify any token
- **Anti-substitution** — the `s3Key` is embedded in the payload; a token for file A cannot redeem file B
- **Timing-attack resistant** — signature comparison uses `MessageDigest.isEqual()` (constant-time)
- **Versionable** — the `v1` suffix enables future secret rotation without breaking issued tokens

When a client calls `GET /api/v1/download?token=...`, the service validates the token and issues a 302 redirect to a short-lived (60 second) S3 pre-signed `GET` URL. File bytes still flow directly from DO Spaces to the client.

### 4. Audit logging as async, best-effort

`AuditService.record()` is annotated `@Async`. Audit writes run on a separate thread pool and do not block the response path. A failure writing an audit event does not roll back the parent operation.

The `audit_events` table uses `file_upload_id UUID` with `ON DELETE SET NULL` — audit history is preserved even if a file record is deleted.

### 5. Schema managed by Flyway

Hibernate is set to `ddl-auto: validate` — it only checks that the schema matches entity definitions. All DDL changes go through versioned Flyway migrations in `src/main/resources/db/migration/`. This ensures deterministic, auditable schema evolution in every environment.

---

## Data Model

```
┌────────────┐       ┌──────────────┐       ┌──────────────┐
│   users    │       │ file_uploads │       │ file_chunks  │
├────────────┤  1    ├──────────────┤  1    ├──────────────┤
│ id (UUID)  │  ───► │ id (UUID)    │  ───► │ id (UUID)    │
│ username   │  N    │ owner_id     │  N    │file_upload_id│
│ email      │       │ filename     │       │ chunk_number │
│password_has│       │ content_type │       │ chunk_size   │
│ created_at │       │ total_size   │       │ etag         │
└────────────┘       │ total_chunks │       │ chunk_status │
                     │ chunk_size   │       │presigned_url │
                     │ s3_key       │       │ expires_at   │
                     │ s3_upload_id │       └──────────────┘
                     │upload_status │
                     │ created_at   │       ┌──────────────┐
                     │ completed_at │       │ audit_events │
                     └──────────────┘       ├──────────────┤
                             │              │ id (UUID)    │
                             └─────────────►│file_upload_id│ (nullable)
                                            │ event_type   │
                                            │ actor_id     │
                                            │ ip_address   │
                                            │ user_agent   │
                                            │ metadata JSONB│
                                            │ created_at   │
                                            └──────────────┘
```

### Upload status lifecycle

```
INITIATED ──► IN_PROGRESS ──► COMPLETED
    │               │
    └───────────────┴──► FAILED
                         ABORTED
```

### Chunk status lifecycle

```
NOT_UPLOADED ──► UPLOADING ──► UPLOADED
```

### Audit event types

| Event | Trigger |
|---|---|
| `UPLOAD_INITIATED` | `POST /files/upload` — multipart upload created in DO Spaces |
| `CHUNK_UPLOAD_STARTED` | `GET /files/{id}/chunks/{n}/presigned-url` — pre-signed URL issued |
| `CHUNK_UPLOADED` | `POST /files/{id}/chunks/{n}/confirm` — client confirmed ETag |
| `UPLOAD_COMPLETED` | `POST /files/{id}/complete` — S3 CompleteMultipartUpload succeeded |
| `UPLOAD_FAILED` | `POST /files/{id}/complete` — S3 CompleteMultipartUpload failed |
| `DOWNLOAD_LINK_GENERATED` | `POST /files/{id}/download-link` — signed token issued |
| `FILE_ACCESSED` | `GET /download?token=...` — token validated and redirect served |

---

## API Reference

All endpoints under `/api/v1/files/**` require `Authorization: Bearer <jwt>`. The download endpoint is public.

### Authentication

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/auth/register` | Register a new user. Returns JWT. |
| `POST` | `/api/v1/auth/login` | Login with username + password. Returns JWT. |

**Register request:**
```json
{
  "username": "alice",
  "email": "alice@example.com",
  "password": "securepassword"
}
```

**Auth response:**
```json
{ "token": "eyJ...", "username": "alice", "tokenType": "Bearer" }
```

---

### File Upload (Multipart)

#### Step 1 — Initiate upload

```
POST /api/v1/files/upload
Authorization: Bearer <jwt>
```

```json
{
  "filename": "archive.tar.gz",
  "contentType": "application/gzip",
  "totalSize": 1073741824,
  "chunkSize": 10485760
}
```

`chunkSize` is optional — defaults to 10 MB. Minimum is 5 MB (S3 requirement). `totalSize` maximum is 50 GB.

**Response `201`:**
```json
{
  "uploadId": "550e8400-e29b-41d4-a716-446655440000",
  "filename": "archive.tar.gz",
  "totalChunks": 103,
  "chunkSize": 10485760,
  "uploadStatus": "INITIATED",
  "createdAt": "2025-01-01T10:00:00Z"
}
```

#### Step 2 — Get pre-signed URL for each chunk

```
GET /api/v1/files/{uploadId}/chunks/{chunkNumber}/presigned-url
Authorization: Bearer <jwt>
```

`chunkNumber` is 1-based (1 through `totalChunks`).

**Response `200`:**
```json
{
  "chunkNumber": 1,
  "presignedUrl": "https://bucket.nyc3.digitaloceanspaces.com/uploads/...?X-Amz-Signature=...",
  "expiresAt": "2025-01-01T11:00:00Z",
  "chunkStatus": "UPLOADING"
}
```

The service caches the presigned URL — re-calling this endpoint returns the cached URL if it has not yet expired (within 5 minutes of expiry it regenerates). This supports resumable uploads.

#### Step 3 — Upload chunk directly to DO Spaces

```bash
curl -X PUT "<presignedUrl>" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @chunk_001.bin \
  -D -   # print response headers to capture ETag
```

Capture the `ETag` response header — it is required in the next step.

#### Step 4 — Confirm chunk uploaded

```
POST /api/v1/files/{uploadId}/chunks/{chunkNumber}/confirm
Authorization: Bearer <jwt>
```

```json
{ "etag": "\"d8e8fca2dc0f896fd7cb4cb0031ba249\"" }
```

**Response `200`:**
```json
{
  "chunkNumber": 1,
  "chunkStatus": "UPLOADED",
  "etag": "\"d8e8fca2dc0f896fd7cb4cb0031ba249\"",
  "uploadedChunks": 1,
  "totalChunks": 103
}
```

Repeat steps 2–4 for all chunks. Chunks can be uploaded in parallel.

#### Step 5 — Complete upload

```
POST /api/v1/files/{uploadId}/complete
Authorization: Bearer <jwt>
```

All chunks must have status `UPLOADED` before calling this. The service calls `CompleteMultipartUpload` on DO Spaces using the stored ETags.

**Response `200`:**
```json
{
  "uploadId": "550e8400-...",
  "uploadStatus": "COMPLETED",
  "completedAt": "2025-01-01T10:30:00Z"
}
```

---

### File Status and Metadata

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/files` | List all files for the authenticated user (paginated). Supports `?status=COMPLETED&page=0&size=20`. |
| `GET` | `/api/v1/files/{uploadId}` | Detailed status including per-chunk breakdown. |
| `GET` | `/api/v1/files/{uploadId}/audit` | Paginated audit event history for a file. |

**`GET /api/v1/files/{uploadId}` response:**
```json
{
  "uploadId": "...",
  "filename": "archive.tar.gz",
  "uploadStatus": "IN_PROGRESS",
  "totalChunks": 103,
  "uploadedChunks": 47,
  "chunks": [
    { "chunkNumber": 1, "chunkStatus": "UPLOADED", "chunkSize": 10485760 },
    { "chunkNumber": 2, "chunkStatus": "UPLOADING", "chunkSize": 10485760 },
    { "chunkNumber": 3, "chunkStatus": "NOT_UPLOADED", "chunkSize": 10485760 }
  ]
}
```

---

### Download

#### Generate a signed link

```
POST /api/v1/files/{uploadId}/download-link
Authorization: Bearer <jwt>
```

```json
{ "ttlSeconds": 3600 }
```

`ttlSeconds` is optional — defaults to 1 hour. Maximum is 7 days (604800 seconds). File must be in `COMPLETED` status.

**Response `200`:**
```json
{
  "downloadUrl": "https://your-api/api/v1/download?token=eyJ...",
  "expiresAt": "2025-01-01T11:00:00Z"
}
```

#### Redeem the signed link (public, no JWT required)

```
GET /api/v1/download?token=<signed-token>
```

The service validates the HMAC signature and expiry, then responds with `302 Found` redirecting to a 60-second DO Spaces pre-signed GET URL. The client downloads the file directly from DO Spaces.

**Error responses:**
- `401 Unauthorized` — invalid signature
- `410 Gone` — token has expired

---

### Error format

All errors use a consistent envelope:

```json
{
  "status": 409,
  "error": "Conflict",
  "message": "Chunks not yet uploaded: [3, 7, 12]",
  "timestamp": "2025-01-01T10:00:00Z"
}
```

---

## Security Model

| Concern | Mechanism |
|---|---|
| **API authentication** | HMAC-SHA256 signed JWTs (JJWT 0.12). Tokens expire in 24h. |
| **Ownership enforcement** | All file endpoints query `WHERE id = ? AND owner_id = ?`. A user cannot read or modify another user's files. |
| **Download authorization** | HMAC-SHA256 signed tokens with embedded `fileId`, `s3Key`, and expiry epoch. No session required. |
| **Timing-attack resistance** | Token signature comparison uses `MessageDigest.isEqual()` (constant-time). |
| **Path traversal prevention** | `s3Key` construction sanitizes the filename: `[^a-zA-Z0-9._\-]` is replaced with `_`. |
| **Password storage** | BCrypt with Spring Security's `BCryptPasswordEncoder` (default strength 10). |
| **CSRF** | Disabled — stateless JWT/HMAC API; no cookies used. |
| **Session management** | `STATELESS` — no HTTP sessions created or used. |

---

## How to Run

### Prerequisites

- A DigitalOcean Spaces bucket (or any S3-compatible object store)
- Docker + Docker Compose plugin **— or —** Java 17+ and PostgreSQL 14+ for a native run

---

### Deploy with Docker (recommended)

The entire stack (app + database) runs with a single command. No local Java or PostgreSQL install required.

**1. Copy the environment template:**

```bash
cp .env.example .env
```

**2. Fill in your credentials in `.env`:**

```env
# DigitalOcean Spaces
DO_SPACES_ACCESS_KEY=your_key
DO_SPACES_SECRET_KEY=your_secret
DO_SPACES_ENDPOINT=https://nyc3.digitaloceanspaces.com
DO_SPACES_REGION=nyc3
DO_SPACES_BUCKET=your-bucket-name

# Secrets — generate strong random values:
#   openssl rand -base64 48
JWT_SECRET=at_least_32_characters_here
HMAC_SECRET=a_different_32_character_secret
```

> **Generating secrets:**
> ```bash
> openssl rand -base64 48   # run twice — one output for JWT_SECRET, one for HMAC_SECRET
> ```

**3. Build and start:**

```bash
docker compose up --build -d
```

That's it. Docker Compose will:
- Start PostgreSQL 16, wait for it to pass its health check
- Build the application image from the local `Dockerfile`
- Start the app; Flyway migrations run automatically on first boot
- Expose the API on `http://localhost:8080` (override with `APP_PORT=9090` in `.env`)

**Check that everything is healthy:**

```bash
docker compose ps          # both services should show "healthy"
docker compose logs -f app # tail the app logs
curl http://localhost:8080/actuator/health
```

**Stop the stack:**

```bash
docker compose down        # keeps the postgres_data volume (data is preserved)
docker compose down -v     # also removes the volume (wipes the database)
```

**Rebuild after a code change:**

```bash
docker compose up --build -d
```

---

### Run natively (Java + local PostgreSQL)

Use this if you prefer running the app outside a container during development.

**Prerequisites:** Java 17+, PostgreSQL 14+

**1. Copy and edit `.env`** (same as above), then source it:

```bash
cp .env.example .env
# edit .env
set -a; source .env; set +a
```

**2. Start PostgreSQL:**

```bash
# Ubuntu / Debian
sudo apt-get install -y postgresql
sudo service postgresql start
sudo -u postgres psql -c "CREATE USER fileshare WITH PASSWORD 'fileshare_secret';"
sudo -u postgres psql -c "CREATE DATABASE fileshare OWNER fileshare;"
sudo -u postgres psql -c "GRANT ALL PRIVILEGES ON DATABASE fileshare TO fileshare;"
```

```bash
# macOS (Homebrew)
brew install postgresql@16 && brew services start postgresql@16
createuser -s fileshare && createdb fileshare -O fileshare
psql -c "ALTER USER fileshare WITH PASSWORD 'fileshare_secret';"
```

**3. Run the service:**

```bash
./gradlew bootRun
```

The API is available at `http://localhost:8080`. Flyway migrations run automatically on startup.

---

### Verifying the setup

```bash
# Register a user
curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"password123"}' | jq .

# Save the token
TOKEN="eyJ..."

# Initiate a 20 MB upload (2 chunks)
curl -s -X POST http://localhost:8080/api/v1/files/upload \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"filename":"test.bin","contentType":"application/octet-stream","totalSize":20971520}' | jq .

# Check health
curl http://localhost:8080/actuator/health
```

---

## Configuration Reference

All application config is in `src/main/resources/application.yml`. Values are read from environment variables with sensible defaults where possible.

| Environment Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/fileshare` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `fileshare` | Database username |
| `SPRING_DATASOURCE_PASSWORD` | `fileshare_secret` | Database password |
| `APP_DO_SPACES_ACCESS_KEY` | *(required)* | DO Spaces access key |
| `APP_DO_SPACES_SECRET_KEY` | *(required)* | DO Spaces secret key |
| `APP_DO_SPACES_ENDPOINT` | `https://nyc3.digitaloceanspaces.com` | Spaces endpoint URL |
| `APP_DO_SPACES_REGION` | `nyc3` | Spaces region |
| `APP_DO_SPACES_BUCKET` | *(required)* | Bucket name |
| `APP_JWT_SECRET` | *(required)* | JWT signing secret — min 32 characters |
| `APP_HMAC_SECRET` | *(required)* | HMAC signing secret — min 32 characters, **must differ from JWT secret** |

**Tunable application properties (in `application.yml`):**

| Property | Default | Description |
|---|---|---|
| `app.do-spaces.presigned-url-ttl-minutes` | `60` | How long upload part pre-signed URLs remain valid |
| `app.hmac.default-link-ttl-seconds` | `3600` | Default download link TTL if not specified in request |
| `app.upload.default-chunk-size-bytes` | `10485760` (10 MB) | Default chunk size |
| `app.upload.min-chunk-size-bytes` | `5242880` (5 MB) | Minimum chunk size (S3 hard requirement) |
| `app.upload.max-file-size-bytes` | `53687091200` (50 GB) | Maximum file size |

---

## Testing Strategy

The project uses three layers of tests.

### 1. Unit tests — `HmacSignatureUtilTest`, `FileServiceTest`

Pure unit tests with no Spring context. `FileServiceTest` uses Mockito to isolate `FileService` from all its dependencies (storage, database, audit).

**What is covered:**
- HMAC token generation, validation, expiry, tamper-detection, cross-secret isolation
- Upload initiation: chunk count calculation, file size limits, chunk size enforcement
- Chunk confirmation: state transitions, ETag persistence, progress tracking
- Upload completion: all-chunks-required guard, sorted ETag assembly, failure handling
- File status: correct `uploadedChunks` count from mixed-status chunk lists

```bash
./gradlew test --tests "com.fileshare.util.HmacSignatureUtilTest"
./gradlew test --tests "com.fileshare.service.FileServiceTest"
```

### 2. Controller slice tests — `FileControllerTest`

Uses `@WebMvcTest` to load only the web layer (controller, security filter chain, exception handler). All service and repository dependencies are `@MockBean`. Tests run without a database or network.

**What is covered:**
- Correct HTTP status codes for happy paths (201, 200)
- Input validation enforcement (missing filename → 400)
- Authentication enforcement (no JWT → 401)
- Service exception mapping (FileNotFoundException → 404, UploadStateException → 409)
- Response body structure (JSON field assertions)

```bash
./gradlew test --tests "com.fileshare.controller.FileControllerTest"
```

### 3. Integration tests — `FileUploadIntegrationTest`

Uses `@SpringBootTest` with the full Spring context against a real PostgreSQL container (Testcontainers). `StorageService` is `@MockBean` so no real DO Spaces bucket is required.

**What is covered:**
- Full multipart upload lifecycle: register → login → initiate → presigned URL → confirm (×N) → complete → download link → download redirect
- Database state assertions (upload status, chunk ETags, `completedAt`)
- Download token: 302 redirect to the mocked S3 URL
- Audit trail populated after lifecycle operations
- Error cases: completing with missing chunks (409), invalid token (401), expired token (410)

```bash
./gradlew test --tests "com.fileshare.integration.FileUploadIntegrationTest"
```

> Testcontainers automatically pulls and starts a `postgres:16-alpine` container for the duration of the test run. Docker must be running.

### Running all tests

```bash
./gradlew test
```

Test results are written to `build/reports/tests/test/index.html`.

### Test configuration

Integration and slice tests use the `test` Spring profile (`src/test/resources/application-test.yml`), which:
- Provides stub values for DO Spaces and secret keys so the Spring context starts without real credentials
- Enables SQL logging for easier debugging
- Is overridden by Testcontainers' `@DynamicPropertySource` for the actual database URL

---

## Project Structure

```
src/
├── main/java/com/fileshare/
│   ├── FileShareApplication.java          # Entry point, @EnableAsync
│   ├── config/
│   │   ├── AppProperties.java             # Typed config binding (@ConfigurationProperties)
│   │   ├── S3Config.java                  # S3Client + S3Presigner beans (path-style for DO Spaces)
│   │   └── SecurityConfig.java            # Spring Security filter chain, BCrypt bean
│   ├── controller/
│   │   ├── AuthController.java            # POST /auth/register, /auth/login
│   │   ├── FileController.java            # All /files/** endpoints
│   │   └── DownloadController.java        # GET /download (public, HMAC-validated)
│   ├── service/
│   │   ├── AuthService.java               # Register, login, BCrypt password check
│   │   ├── FileService.java               # Core upload/download/status orchestration
│   │   ├── StorageService.java            # All DO Spaces / S3 SDK calls
│   │   ├── AuditService.java              # @Async audit event recording
│   │   ├── DownloadService.java           # HMAC token validation, redirect URL generation
│   │   └── JwtService.java                # JWT generation and validation (JJWT 0.12)
│   ├── domain/
│   │   ├── User.java
│   │   ├── FileUpload.java                # Core file record with S3 metadata
│   │   ├── FileChunk.java                 # Per-chunk tracking (status, ETag, presigned URL cache)
│   │   ├── AuditEvent.java                # Append-only audit log entry
│   │   └── enums/                         # UploadStatus, ChunkStatus, AuditEventType
│   ├── repository/                        # Spring Data JPA repositories
│   ├── dto/request/                       # Validated inbound request records
│   ├── dto/response/                      # Outbound response records
│   ├── security/
│   │   ├── JwtAuthenticationFilter.java   # Extracts JWT subject into SecurityContext
│   │   └── JwtAuthenticationEntryPoint.java  # Returns JSON 401 on auth failure
│   ├── util/
│   │   └── HmacSignatureUtil.java         # Token creation + constant-time validation
│   └── exception/
│       ├── GlobalExceptionHandler.java    # @RestControllerAdvice, maps exceptions to HTTP
│       └── *.java                         # Typed domain exceptions
└── main/resources/
    ├── application.yml
    └── db/migration/
        ├── V1__create_users.sql
        ├── V2__create_file_uploads.sql
        ├── V3__create_file_chunks.sql
        ├── V4__create_audit_events.sql
        └── V5__create_indexes.sql
```
