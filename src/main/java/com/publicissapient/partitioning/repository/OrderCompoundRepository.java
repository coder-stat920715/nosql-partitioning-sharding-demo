package com.publicissapient.partitioning.repository;

import com.publicissapient.partitioning.domain.OrderCompoundEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OrderCompoundRepository extends MongoRepository<OrderCompoundEntity, String> {

    // Fully targeted: both parts of the compound shard key present -> single shard hit.
    List<OrderCompoundEntity> findByRegionAndCustomerId(String region, String customerId);

    // Partially targeted: only the shard-key prefix (region) present -> routes to the
    // subset of chunks/shards owning that region, not a full cluster scatter-gather.
    List<OrderCompoundEntity> findByRegion(String region);

    // No shard-key field present at all -> full scatter-gather across all shards.
    List<OrderCompoundEntity> findByStatus(String status);
}
