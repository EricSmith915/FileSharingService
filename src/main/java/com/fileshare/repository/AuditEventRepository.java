package com.fileshare.repository;

import com.fileshare.domain.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findByFileUploadIdOrderByCreatedAtDesc(UUID fileUploadId, Pageable pageable);

    Page<AuditEvent> findByActorIdOrderByCreatedAtDesc(String actorId, Pageable pageable);
}
