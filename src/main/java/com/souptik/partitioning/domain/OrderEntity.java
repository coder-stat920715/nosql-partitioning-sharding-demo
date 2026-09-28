package com.souptik.partitioning.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.Sharded;
import org.springframework.data.mongodb.core.mapping.ShardingStrategy;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * HASH-BASED sharded collection: `orders`
 *
 * Shard key: { customerId: "hashed" }
 *
 * Why hashed?  customerId values are effectively random/opaque UUID-like
 * identifiers. Hashing them spreads writes evenly across shard01 and
 * shard02, avoiding the classic "hot shard" problem you get from
 * monotonically increasing keys (auto-increment IDs, timestamps).
 *
 * Trade-off: because the shard key is hashed, range queries on customerId
 * ("give me all customers between X and Y") can no longer be routed to a
 * contiguous set of chunks -- but for this collection we only ever do
 * point lookups by customerId, so that's an acceptable trade-off.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "orders")
@Sharded(shardKey = "customerId", shardingStrategy = ShardingStrategy.HASH)
@CompoundIndexes({
        @CompoundIndex(name = "customer_status_idx", def = "{'customerId': 1, 'status': 1}"),
        @CompoundIndex(name = "customer_createdAt_idx", def = "{'customerId': 1, 'createdAt': -1}")
})
public class OrderEntity {

    @Id
    private String id;

    /** Shard key field (hashed). Every targeted query MUST include this. */
    @Indexed
    private String customerId;

    @Field(targetType = FieldType.STRING)
    private String orderNumber;

    private BigDecimal orderAmount;

    @Indexed
    private String status; // PLACED, CONFIRMED, SHIPPED, DELIVERED, CANCELLED

    private String region; // used only for reporting on this collection, NOT the shard key

    private Instant createdAt;

    private Instant updatedAt;
}
