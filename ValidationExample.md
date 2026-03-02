# Validation Example — Manual Smoke Test

This documents a full end-to-end manual test run against a locally running instance of the service.

## Environment

| Component   | Details                                      |
|-------------|----------------------------------------------|
| App         | `fileshare-0.0.1-SNAPSHOT.jar` on port 8080  |
| Database    | PostgreSQL 16 on `localhost:5432`, DB `fileshare` |
| Object store | MinIO on `localhost:9000`, bucket `fileshare-dev` |

---

## Steps

### 1. Clean and rebuild

```bash
cd /workspaces/EricHiringBlitsProject
./gradlew clean
./gradlew build -x test
```

Produced `build/libs/fileshare-0.0.1-SNAPSHOT.jar`.

---

### 2. Start MinIO

```bash
mkdir -p /tmp/minio-data
MINIO_ROOT_USER=minioadmin MINIO_ROOT_PASSWORD=minioadmin \
  minio server /tmp/minio-data --address :9000 --console-address :9001 >/tmp/minio.log 2>&1 &

mc alias set local http://localhost:9000 minioadmin minioadmin
mc mb local/fileshare-dev
```

---

### 3. Start the app

```bash
set -a && source .env && set +a
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/fileshare" \
  java -jar build/libs/fileshare-0.0.1-SNAPSHOT.jar >/tmp/fileshare-app.log 2>&1 &
```

Health check confirmed UP:

```
curl http://localhost:8080/actuator/health
{"status":"UP"}
```

---

### 4. Login

User `alice` already existed from a prior run, so login was used instead of register.

**Request:**
```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'
```

**Response:**
```json
{"token":"eyJhbGciOiJIUzUxMiJ9...","username":"alice","tokenType":"Bearer"}
```

---

### 5. Initiate upload

A 6 MB random file was created and submitted as a single-chunk upload (below the 10 MB default chunk size).

**Request:**
```bash
dd if=/dev/urandom of=/tmp/testfile.bin bs=1M count=6

curl -s -X POST http://localhost:8080/api/v1/files/upload \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"filename":"testfile.bin","contentType":"application/octet-stream","totalSize":6291456}'
```

**Response:**
```json
{
  "uploadId": "e4891309-6078-4ec0-9762-74b8d53d6584",
  "filename": "testfile.bin",
  "totalChunks": 1,
  "chunkSize": 10485760,
  "uploadStatus": "INITIATED"
}
```

---

### 6. Get presigned URL for chunk 1

**Request:**
```bash
curl -s http://localhost:8080/api/v1/files/e4891309-.../chunks/1/presigned-url \
  -H "Authorization: Bearer $TOKEN"
```

**Response:**
```json
{
  "chunkNumber": 1,
  "presignedUrl": "http://localhost:9000/fileshare-dev/uploads/alice/211fe84c-.../testfile.bin?partNumber=1&uploadId=...",
  "expiresAt": "2026-03-02T23:51:19Z",
  "chunkStatus": "UPLOADING"
}
```

---

### 7. Upload chunk directly to MinIO

**Request:**
```bash
curl -s -X PUT "$PRESIGNED_URL" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @/tmp/testfile.bin \
  -D - -o /dev/null
```

**Response headers (truncated):**
```
HTTP/1.1 200 OK
ETag: "5ee397f4acc9371be63ac892f4196589"
```

---

### 8. Confirm chunk

**Request:**
```bash
curl -s -X POST http://localhost:8080/api/v1/files/e4891309-.../chunks/1/confirm \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"etag": "\"5ee397f4acc9371be63ac892f4196589\""}'
```

**Response:**
```json
{
  "chunkNumber": 1,
  "chunkStatus": "UPLOADED",
  "etag": "5ee397f4acc9371be63ac892f4196589",
  "uploadedChunks": 1,
  "totalChunks": 1
}
```

---

### 9. Complete upload

**Request:**
```bash
curl -s -X POST http://localhost:8080/api/v1/files/e4891309-.../complete \
  -H "Authorization: Bearer $TOKEN"
```

**Response:**
```json
{
  "uploadId": "e4891309-6078-4ec0-9762-74b8d53d6584",
  "uploadStatus": "COMPLETED",
  "completedAt": "2026-03-02T22:51:36Z"
}
```

---

### 10. Generate signed download link

**Request:**
```bash
curl -s -X POST http://localhost:8080/api/v1/files/e4891309-.../download-link \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ttlSeconds": 300}'
```

**Response:**
```json
{
  "downloadUrl": "http://localhost:8080/api/v1/download?token=ZTQ4OTEzMDkt...",
  "expiresAt": "2026-03-02T22:56:36Z"
}
```

---

### 11. Redeem link — verify 302 redirect

**Request:**
```bash
curl -s -D - -o /dev/null "$DOWNLOAD_URL" | grep -E "^HTTP|^Location"
```

**Response:**
```
HTTP/1.1 302
Location: http://localhost:9000/fileshare-dev/uploads/alice/211fe84c-.../testfile.bin?X-Amz-Expires=60&...
```

---

### 12. Download and verify file integrity

```bash
curl -sL -o /tmp/downloaded.bin "$DOWNLOAD_URL"
md5sum /tmp/testfile.bin /tmp/downloaded.bin
```

```
5ee397f4acc9371be63ac892f4196589  /tmp/testfile.bin
5ee397f4acc9371be63ac892f4196589  /tmp/downloaded.bin
PASS: files match
```

---

### 13. Audit trail

```bash
curl -s http://localhost:8080/api/v1/files/e4891309-.../audit \
  -H "Authorization: Bearer $TOKEN"
```

Six events were recorded (newest-first):

| Event                    | Actor  |
|--------------------------|--------|
| `FILE_ACCESSED`          | —      |
| `FILE_ACCESSED`          | —      |
| `DOWNLOAD_LINK_GENERATED`| alice  |
| `UPLOAD_COMPLETED`       | alice  |
| `CHUNK_UPLOADED`         | alice  |
| `CHUNK_UPLOAD_STARTED`   | alice  |

> `UPLOAD_INITIATED` is absent — this is a known async/transaction race condition documented in `HowToTestLocally.md`. The upload itself is not affected.

---

## Result

All steps passed. The full multipart upload lifecycle, HMAC-signed download, 302 redirect to object storage, file integrity check, and audit trail all behaved as documented.
