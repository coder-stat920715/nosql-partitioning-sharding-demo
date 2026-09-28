package com.souptik.partitioning.repository;

import com.souptik.partitioning.domain.OrderEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

/**
 * Standard Spring Data repository for the hash-sharded `orders` collection.
 *
 * findByCustomerId is a TARGETED query: it includes the shard key
 * (customerId) in the filter, so mongos routes it directly to the single
 * shard that owns that hashed key range -- O(1) routing overhead,
 * regardless of cluster size.
 */
public interface OrderRepository extends MongoRepository<OrderEntity, String>, CustomOrderRepository {

    List<OrderEntity> findByCustomerId(String customerId);

    List<OrderEntity> findByCustomerIdAndStatus(String customerId, String status);

    // Deliberately shard-key-less query -- used to demonstrate scatter-gather.
    @Query("{ 'status': ?0 }")
    List<OrderEntity> findByStatusOnly(String status);
}
