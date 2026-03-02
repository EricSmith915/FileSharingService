-- file_uploads: common query patterns
CREATE INDEX idx_file_uploads_owner_id         ON file_uploads(owner_id);
CREATE INDEX idx_file_uploads_owner_status     ON file_uploads(owner_id, upload_status);
CREATE INDEX idx_file_uploads_created_at       ON file_uploads(created_at DESC);

-- file_chunks: lookup by parent upload
CREATE INDEX idx_file_chunks_upload_id         ON file_chunks(file_upload_id);
CREATE INDEX idx_file_chunks_upload_status     ON file_chunks(file_upload_id, chunk_status);

-- audit_events: lookup by file and by actor
CREATE INDEX idx_audit_events_file_upload_id   ON audit_events(file_upload_id);
CREATE INDEX idx_audit_events_actor_id         ON audit_events(actor_id);
CREATE INDEX idx_audit_events_created_at       ON audit_events(created_at DESC);
CREATE INDEX idx_audit_events_event_type       ON audit_events(event_type);
