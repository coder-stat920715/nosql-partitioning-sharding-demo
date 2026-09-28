package com.souptik.partitioning.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;
import org.springframework.data.mongodb.core.mapping.ShardingStrategy;

import java.time.Instant;
import java.util.List;

/**
 * LIST / DIRECTORY / MULTI-TENANT partitioned collection: `tenant_data`
 *
 * Shard key: { tenantRegion: 1, tenantId: 1 }
 *
 * This models classic "directory-based partitioning": a lookup dimension
 * (tenantRegion -- think US / EU / APAC data-residency zones) explicitly
 * groups tenants into named buckets, and within a bucket tenantId provides
 * per-tenant isolation. In a real directory-partitioning system you'd keep
 * an explicit routing table (tenant -> physical partition); here MongoDB's
 * own chunk map plays that role once the collection is sharded on this key.
 *
 * This is also the pattern used by the DynamicPartitionRouter
 * (see router package) to additionally demonstrate *application-level*
 * routing to different logical collections per tenant/region, independent
 * of what the database-level shard key is doing -- a technique used when
 * regulatory requirements demand physically separate collections/databases
 * per region rather than relying purely on shard placement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "tenant_data")
@Sharded(shardKey = {"tenantRegion", "tenantId"}, shardingStrategy = ShardingStrategy.RANGE)
public class MultiTenantDataEntity {

    @Id
    private String id;

    @Indexed
    private String tenantRegion; // US, EU, APAC -- directory bucket / zone

    @Indexed
    private String tenantId;     // part of compound shard key

    private String dataKey;

    private String dataValue;

    /** Multi-key index example: querying by any single tag indexes each array element. */
    @Indexed
    private List<String> tags;

    private Instant createdAt;
}
