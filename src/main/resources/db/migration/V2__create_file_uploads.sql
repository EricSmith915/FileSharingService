-- Core file upload tracking table
CREATE TABLE file_uploads (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    owner_id        VARCHAR(255)    NOT NULL,
    filename        VARCHAR(1024)   NOT NULL,
    content_type    VARCHAR(255),
    total_size      BIGINT          NOT NULL,
    total_chunks    INTEGER         NOT NULL,
    chunk_size      BIGINT          NOT NULL,

    -- Object key in DO Spaces, e.g. uploads/{ownerId}/{uuid}/{filename}
    s3_key          VARCHAR(2048)   NOT NULL,

    -- Multipart upload ID returned by DO Spaces / S3
    s3_upload_id    VARCHAR(2048),

    -- INITIATED | IN_PROGRESS | COMPLETED | FAILED | ABORTED
    upload_status   VARCHAR(32)     NOT NULL DEFAULT 'INITIATED',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,

    CONSTRAINT pk_file_uploads PRIMARY KEY (id)
);

COMMENT ON COLUMN file_uploads.s3_key IS 'The object key in DO Spaces';
COMMENT ON COLUMN file_uploads.s3_upload_id IS 'The multipart upload ID returned by DO Spaces / S3';
COMMENT ON COLUMN file_uploads.chunk_size IS 'Size of each chunk in bytes; last chunk may be smaller';
