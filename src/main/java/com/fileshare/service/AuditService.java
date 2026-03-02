package com.fileshare.service;

import com.fileshare.domain.AuditEvent;
import com.fileshare.domain.FileUpload;
import com.fileshare.domain.enums.AuditEventType;
import com.fileshare.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    /**
     * Records an audit event asynchronously so it does not block the request path.
     * Audit writes are best-effort: a failure here does not roll back the parent transaction.
     */
    @Async
    public void record(FileUpload fileUpload,
                       AuditEventType eventType,
                       String actorId,
                       String ipAddress,
                       String userAgent,
                       Map<String, Object> metadata) {
        AuditEvent event = AuditEvent.builder()
                .fileUpload(fileUpload)
                .eventType(eventType)
                .actorId(actorId)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .metadata(metadata)
                .build();
        auditEventRepository.save(event);
    }

    public void record(FileUpload fileUpload, AuditEventType eventType, String actorId,
                       String ipAddress, String userAgent) {
        record(fileUpload, eventType, actorId, ipAddress, userAgent, null);
    }
}
