-- Per-chunk tracking for multipart uploads
CREATE TABLE file_chunks (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    file_upload_id              UUID            NOT NULL,

    -- 1-based chunk index matching the S3 multipart part number
    chunk_number                INTEGER         NOT NULL,
    chunk_size                  BIGINT,

    -- ETag returned by S3 after the chunk is uploaded; required for CompleteMultipartUpload
    etag                        VARCHAR(512),

    -- NOT_UPLOADED | UPLOADING | UPLOADED
    chunk_status                VARCHAR(32)     NOT NULL DEFAULT 'NOT_UPLOADED',

    -- Cached pre-signed PUT URL; avoids regenerating S3 URL on every client poll
    presigned_url               TEXT,
    presigned_url_expires_at    TIMESTAMPTZ,

    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_file_chunks PRIMARY KEY (id),
    CONSTRAINT fk_file_chunks_upload
        FOREIGN KEY (file_upload_id)
        REFERENCES file_uploads(id)
        ON DELETE CASCADE,
    CONSTRAINT uq_file_chunks_upload_chunk
        UNIQUE (file_upload_id, chunk_number)
);

COMMENT ON COLUMN file_chunks.chunk_number IS '1-based index matching S3 part number';
COMMENT ON COLUMN file_chunks.etag IS 'ETag from S3; required for CompleteMultipartUpload';
