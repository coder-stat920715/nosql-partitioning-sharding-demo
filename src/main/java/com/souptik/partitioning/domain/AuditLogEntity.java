package com.souptik.partitioning.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;
import org.springframework.data.mongodb.core.mapping.ShardingStrategy;

import java.time.Instant;
import java.util.Map;

/**
 * RANGE-partitioned collection: `audit_logs`
 *
 * Shard key: { createdAt: 1 }  (RANGE strategy)
 *
 * Range/time-series partitioning keeps chronologically adjacent audit
 * events physically co-located, which is exactly what you want for
 * "last 24 hours" / "this billing period" style audit queries -- they
 * become targeted, contiguous-chunk scans instead of full scatter-gather.
 *
 * The well-known risk (and a favourite interview question) is the
 * "monotonically increasing shard key" write hotspot: because createdAt is
 * always increasing, every new insert would land in the single chunk that
 * currently owns the highest range -- which pins 100% of insert traffic on
 * one shard until the balancer intervenes. In production this collection
 * would be PRE-SPLIT (via `sh.splitAt` / zoned sharding by time bucket) so
 * new chunks exist ahead of the write curve, or the balancer would be tuned
 * with a tighter chunk size. This is called out explicitly in the README.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "audit_logs")
@Sharded(shardKey = "createdAt", shardingStrategy = ShardingStrategy.RANGE)
@CompoundIndex(name = "eventType_createdAt_idx", def = "{'eventType': 1, 'createdAt': -1}")
public class AuditLogEntity {

    @Id
    private String id;

    @Indexed
    private Instant createdAt; // shard key (range)

    @Indexed
    private String eventType; // LOGIN, ORDER_PLACED, PAYMENT_FAILED, ...

    private String actorId;

    private String resourceId;

    private String severity; // INFO, WARN, ERROR, CRITICAL

    private Map<String, Object> metadata;
}
