package com.souptik.partitioning.service;

import com.souptik.partitioning.domain.AuditLogEntity;
import com.souptik.partitioning.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Service for the range-sharded `audit_logs` collection (shard key: createdAt).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogEntity recordEvent(String eventType, String actorId, String resourceId,
                                       String severity, Map<String, Object> metadata) {
        AuditLogEntity entity = AuditLogEntity.builder()
                .createdAt(Instant.now())
                .eventType(eventType)
                .actorId(actorId)
                .resourceId(resourceId)
                .severity(severity)
                .metadata(metadata)
                .build();
        return auditLogRepository.save(entity);
    }

    /** TARGETED range read: createdAt (the shard key) bounds the query to a contiguous chunk range. */
    public List<AuditLogEntity> findEventsInWindow(Instant from, Instant to) {
        return auditLogRepository.findByCreatedAtBetween(from, to);
    }

    /** SCATTER-GATHER read: eventType is not the shard key -> broadcast to all shards. */
    public List<AuditLogEntity> findByEventType(String eventType) {
        return auditLogRepository.findByEventType(eventType);
    }
}
