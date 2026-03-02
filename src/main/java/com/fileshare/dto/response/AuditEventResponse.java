package com.fileshare.dto.response;

import com.fileshare.domain.enums.AuditEventType;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        AuditEventType eventType,
        String actorId,
        String ipAddress,
        Map<String, Object> metadata,
        OffsetDateTime createdAt
) {}
