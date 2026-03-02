package com.fileshare.domain.enums;

public enum AuditEventType {
    UPLOAD_INITIATED,
    CHUNK_UPLOAD_STARTED,
    CHUNK_UPLOADED,
    UPLOAD_COMPLETED,
    UPLOAD_FAILED,
    DOWNLOAD_LINK_GENERATED,
    FILE_ACCESSED
}
