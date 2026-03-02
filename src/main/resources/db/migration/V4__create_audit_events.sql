-- Append-only audit log; file_upload_id is nullable so records survive file deletion
CREATE TABLE audit_events (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),

    -- Nullable: preserved even if the parent file is deleted
    file_upload_id  UUID,

    -- UPLOAD_INITIATED | CHUNK_UPLOAD_STARTED | CHUNK_UPLOADED | UPLOAD_COMPLETED
    -- UPLOAD_FAILED | DOWNLOAD_LINK_GENERATED | FILE_ACCESSED
    event_type      VARCHAR(64)     NOT NULL,

    -- JWT subject (username) who triggered the event; null for public download access
    actor_id        VARCHAR(255),
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(1024),

    -- Flexible JSONB payload for event-specific data (chunk number, TTL, etc.)
    metadata        JSONB,

    created_at      TIMESTAMPTZ         NOT NULL DEFAULT now(),

    CONSTRAINT pk_audit_events PRIMARY KEY (id),
    CONSTRAINT fk_audit_events_upload
        FOREIGN KEY (file_upload_id)
        REFERENCES file_uploads(id)
        ON DELETE SET NULL
);

COMMENT ON COLUMN audit_events.actor_id IS 'JWT subject (username); null for unauthenticated access';
COMMENT ON COLUMN audit_events.metadata IS 'Event-specific data: chunk number, expiry time, etc.';
