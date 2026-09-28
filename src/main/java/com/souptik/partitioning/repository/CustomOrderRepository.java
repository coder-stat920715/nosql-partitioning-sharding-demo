package com.souptik.partitioning.repository;

import com.souptik.partitioning.domain.OrderEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Custom repository contract for operations that need raw MongoTemplate
 * access: aggregation pipelines and explicit cross-partition (scatter)
 * queries that Spring Data's derived-query mechanism cannot express.
 */
public interface CustomOrderRepository {

    /** Aggregation pipeline: total order amount grouped by status, cluster-wide. */
    List<Map<String, Object>> aggregateOrderTotalsByStatus();

    /** Aggregation pipeline: total order amount per customer, scoped to one customer (targeted). */
    BigDecimal aggregateTotalSpendForCustomer(String customerId);

    /** Range-based cross-partition query on orderAmount without using the shard key. */
    List<OrderEntity> findOrdersWithAmountGreaterThan(BigDecimal threshold);
}
