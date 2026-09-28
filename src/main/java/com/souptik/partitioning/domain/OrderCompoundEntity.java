package com.souptik.partitioning.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;
import org.springframework.data.mongodb.core.mapping.ShardingStrategy;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * COMPOUND shard key collection: `orders_compound`
 *
 * Shard key: { region: 1, customerId: 1 }  (RANGE strategy across the compound key)
 *
 * This is the canonical example asked about in interviews: a compound shard
 * key balances two competing goals --
 *
 *   1. Query isolation: `region` is the coarse-grained prefix, so any query
 *      that filters by region alone can be routed to the subset of chunks
 *      (and therefore the subset of shards) that own that region -- it does
 *      NOT need to be a single-shard hit, but it avoids a full scatter-gather
 *      across every shard in the cluster when the cluster grows.
 *
 *   2. Write distribution: within a single region, `customerId` adds enough
 *      cardinality that writes for that region don't all land in one chunk
 *      (which would happen if region alone were the shard key, since there
 *      are only a handful of regions).
 *
 * A query that supplies BOTH region and customerId is a fully targeted,
 * single-shard operation. A query with only `region` is a "directed"
 * scatter-gather across the chunks that belong to that region. A query with
 * neither is a full scatter-gather across the whole cluster.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "orders_compound")
@Sharded(shardKey = {"region", "customerId"}, shardingStrategy = ShardingStrategy.RANGE)
@CompoundIndex(name = "region_customer_idx", def = "{'region': 1, 'customerId': 1}")
public class OrderCompoundEntity {

    @Id
    private String id;

    private String region;       // part 1 of compound shard key (US, EU, APAC ...)

    private String customerId;   // part 2 of compound shard key

    private String orderNumber;

    private BigDecimal orderAmount;

    private String status;

    private Instant createdAt;
}
