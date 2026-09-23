package com.publicissapient.partitioning.repository;

import com.publicissapient.partitioning.domain.AuditLogEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends MongoRepository<AuditLogEntity, String> {

    // Targeted range query: createdAt is the shard key, so a bounded time
    // window resolves to a contiguous run of chunks -- typically a small,
    // predictable subset of shards rather than the whole cluster.
    List<AuditLogEntity> findByCreatedAtBetween(Instant from, Instant to);

    // Shard-key-less query -> scatter-gather across every shard.
    List<AuditLogEntity> findByEventType(String eventType);
}
