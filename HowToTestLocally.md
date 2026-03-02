# How to Test Locally

This guide walks through two ways to verify the service works:

1. **Automated tests** — unit, slice, and integration tests via Gradle (recommended for CI and quick feedback)
2. **Manual end-to-end smoke test** — spin up the real service and exercise the upload/download flow with `curl`

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 17+ | `java --version` |
| Gradle wrapper | bundled | use `./gradlew`, not a system Gradle |
| PostgreSQL | 14+ | must have the `fileshare` database and user (see below) |
| MinIO | any | S3-compatible local store; binary at `/usr/local/bin/minio` in this devcontainer |
| `mc` (MinIO Client) | any | for creating buckets; also bundled in this devcontainer |
| Docker | any | **only** required for Testcontainers (integration tests). Not needed for the manual smoke test. |

---

## 1. Automated Tests

The test suite is self-contained. Integration tests spin up a real PostgreSQL container via Testcontainers and mock the S3/DO Spaces calls — no external services needed beyond Docker.

```bash
cd /workspaces/EricHiringBlitsProject
./gradlew test
```

### What runs

| Test class | Type | What it covers |
|---|---|---|
| `HmacSignatureUtilTest` | Unit | Token creation, expiry, tamper-detection, cross-secret isolation |
| `FileServiceTest` | Unit | Upload initiation, chunk confirmation, completion, file size limits |
| `FileControllerTest` | Slice (`@WebMvcTest`) | HTTP status codes, auth enforcement, input validation, error mapping |
| `FileUploadIntegrationTest` | Integration (`@SpringBootTest` + Testcontainers) | Full upload lifecycle, download redirect, expired token (410), missing chunks (409) |

### Test report

```
build/reports/tests/test/index.html
```

### Run a single test class

```bash
./gradlew test --tests "com.fileshare.integration.FileUploadIntegrationTest"
./gradlew test --tests "com.fileshare.service.FileServiceTest"
```

---

## 2. Manual End-to-End Smoke Test

This replicates what was done to validate the service works in this devcontainer environment. Every command is copy-pasteable.

### 2a. Set up PostgreSQL

The `fileshare` database and user must exist. Verify with:

```bash
psql "postgresql://fileshare:fileshare_secret@localhost:5432/fileshare" -c "\l"
```

If this fails, create the DB and user:

```bash
sudo -u postgres psql -c "CREATE USER fileshare WITH PASSWORD 'fileshare_secret';"
sudo -u postgres psql -c "CREATE DATABASE fileshare OWNER fileshare;"
sudo -u postgres psql -c "GRANT ALL PRIVILEGES ON DATABASE fileshare TO fileshare;"
```

### 2b. Start MinIO

MinIO is the local S3-compatible store used by the `.env` file.

```bash
mkdir -p /tmp/minio-data
MINIO_ROOT_USER=minioadmin MINIO_ROOT_PASSWORD=minioadmin \
  minio server /tmp/minio-data --address :9000 --console-address :9001 &>/tmp/minio.log &

# Verify it started
curl -s http://localhost:9000/minio/health/live && echo "MinIO is up"
```

Create the bucket if it does not exist:

```bash
mc alias set local http://localhost:9000 minioadmin minioadmin
mc mb local/fileshare-dev 2>/dev/null || echo "bucket already exists"
```

### 2c. Build and start the app

```bash
cd /workspaces/EricHiringBlitsProject

./gradlew build -x test

# Source .env and set the JDBC URL to the local PostgreSQL socket
set -a && source .env && set +a
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/fileshare" \
  java -jar build/libs/*.jar &>/tmp/fileshare-app.log &

# Wait until healthy (retries every 2 s, up to 60 s)
for i in $(seq 1 30); do
  STATUS=$(curl -s http://localhost:8080/actuator/health 2>/dev/null)
  echo "$STATUS" | grep -q '"status":"UP"' && echo "App is UP" && break
  sleep 2
done
```

### 2d. Run the upload flow

Copy the block below and run it all at once. Each step prints its result.

```bash
BASE="http://localhost:8080"

# ---------- Register ----------
echo "=== REGISTER ==="
REGISTER=$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"password123"}')
echo "$REGISTER"
TOKEN=$(echo "$REGISTER" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

# ---------- Initiate upload (6 MB → 1 chunk) ----------
echo ""
echo "=== INITIATE UPLOAD ==="
dd if=/dev/urandom of=/tmp/testfile.bin bs=1M count=6 2>/dev/null
FILE_SIZE=$(stat -c%s /tmp/testfile.bin)

INIT=$(curl -s -X POST "$BASE/api/v1/files/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"filename\":\"testfile.bin\",\"contentType\":\"application/octet-stream\",\"totalSize\":$FILE_SIZE}")
echo "$INIT"
UPLOAD_ID=$(echo "$INIT" | grep -o '"uploadId":"[^"]*"' | cut -d'"' -f4)

# ---------- Get presigned URL for chunk 1 ----------
echo ""
echo "=== PRESIGNED URL ==="
PRESIGNED_JSON=$(curl -s "$BASE/api/v1/files/$UPLOAD_ID/chunks/1/presigned-url" \
  -H "Authorization: Bearer $TOKEN")
echo "$PRESIGNED_JSON"
PRESIGNED_URL=$(echo "$PRESIGNED_JSON" | grep -o '"presignedUrl":"[^"]*"' | cut -d'"' -f4)

# ---------- Upload chunk directly to MinIO ----------
echo ""
echo "=== UPLOAD CHUNK TO MINIO ==="
RESPONSE_HEADERS=$(curl -s -X PUT "$PRESIGNED_URL" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @/tmp/testfile.bin \
  -D - -o /dev/null)
echo "$RESPONSE_HEADERS"
ETAG_RAW=$(echo "$RESPONSE_HEADERS" | grep -i "^etag:" | tr -d '\r' | awk '{print $2}' | tr -d '"')
ETAG="\"$ETAG_RAW\""

# ---------- Confirm chunk ----------
echo ""
echo "=== CONFIRM CHUNK ==="
curl -s -X POST "$BASE/api/v1/files/$UPLOAD_ID/chunks/1/confirm" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"etag\": \"$ETAG\"}"

# ---------- Complete upload ----------
echo ""
echo "=== COMPLETE UPLOAD ==="
curl -s -X POST "$BASE/api/v1/files/$UPLOAD_ID/complete" \
  -H "Authorization: Bearer $TOKEN"

# ---------- Generate download link ----------
echo ""
echo "=== DOWNLOAD LINK ==="
LINK_JSON=$(curl -s -X POST "$BASE/api/v1/files/$UPLOAD_ID/download-link" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ttlSeconds": 300}')
echo "$LINK_JSON"
DOWNLOAD_URL=$(echo "$LINK_JSON" | grep -o '"downloadUrl":"[^"]*"' | cut -d'"' -f4)

# ---------- Verify 302 redirect ----------
echo ""
echo "=== REDEEM LINK (expect 302) ==="
curl -s -D - -o /dev/null "$DOWNLOAD_URL" | grep -E "^HTTP|^Location"

# ---------- Download and check integrity ----------
echo ""
echo "=== FILE INTEGRITY ==="
curl -sL -o /tmp/downloaded.bin "$DOWNLOAD_URL"
ORIG=$(md5sum /tmp/testfile.bin  | awk '{print $1}')
DOWN=$(md5sum /tmp/downloaded.bin | awk '{print $1}')
echo "Original MD5:   $ORIG"
echo "Downloaded MD5: $DOWN"
[ "$ORIG" = "$DOWN" ] && echo "PASS: files match" || echo "FAIL: files differ"
```

### 2e. Check the audit trail

```bash
curl -s "$BASE/api/v1/files/$UPLOAD_ID/audit" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

Expected events (newest-first): `FILE_ACCESSED`, `DOWNLOAD_LINK_GENERATED`, `UPLOAD_COMPLETED`, `CHUNK_UPLOADED`, `CHUNK_UPLOAD_STARTED`.

> **Known issue:** `UPLOAD_INITIATED` is absent from the audit trail due to an async/transaction race condition — the `@Async` audit write fires before the parent transaction commits, causing a FK violation on `file_upload_id`. All other audit events are recorded correctly. The upload itself is not affected.

---

## 3. Stop Everything

```bash
# Kill the Spring Boot app
pkill -f "fileshare.*jar" 2>/dev/null || true

# Kill MinIO
pkill -f "minio server" 2>/dev/null || true
```
