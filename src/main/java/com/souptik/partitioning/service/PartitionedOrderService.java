package com.souptik.partitioning.service;

import com.souptik.partitioning.domain.OrderEntity;
import com.souptik.partitioning.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

/**
 * Core service for the hash-sharded `orders` collection. Exposes three
 * distinct execution mechanics deliberately, so their latency/behaviour can
 * be compared side by side in the interview demo:
 *
 *   1. Targeted write / read  - shard key present, O(1) routing to one shard
 *   2. Scatter-gather read    - shard key absent, fanned out with CompletableFuture
 *   3. Aggregation            - delegated to CustomOrderRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartitionedOrderService {

    private final OrderRepository orderRepository;
    private final Executor scatterGatherExecutor;

    private static final List<String> ALL_STATUSES =
            List.of("PLACED", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED");

    /** TARGETED WRITE: customerId (the shard key) is always populated before insert. */
    public OrderEntity placeOrder(String customerId, BigDecimal orderAmount, String region) {
        OrderEntity order = OrderEntity.builder()
                .customerId(customerId)
                .orderNumber("ORD-" + UUID.randomUUID())
                .orderAmount(orderAmount)
                .status("PLACED")
                .region(region)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        OrderEntity saved = orderRepository.save(order);
        log.info("Targeted write -> customerId={} routed via hashed shard key, orderId={}",
                customerId, saved.getId());
        return saved;
    }

    /**
     * TARGETED READ: includes the shard key (customerId) in the query filter.
     * mongos computes hash(customerId) and routes directly to the one shard
     * that owns that chunk range -- no fan-out, no merge-sort stage.
     */
    public List<OrderEntity> findOrdersForCustomer(String customerId) {
        long start = System.nanoTime();
        List<OrderEntity> result = orderRepository.findByCustomerId(customerId);
        log.info("Targeted read for customerId={} returned {} docs in {} ms",
                customerId, result.size(), elapsedMs(start));
        return result;
    }

    /**
     * SCATTER-GATHER READ: `status` is NOT the shard key on this collection,
     * so mongos must broadcast the query to every shard and merge the
     * results. Here we additionally demonstrate manual parallel fan-out
     * using CompletableFuture across per-status sub-queries purely to make
     * the fan-out/merge pattern visible and timeable at the application
     * layer (in a real deployment mongos already parallelizes shard-level
     * scatter internally; this simulates the same principle client-side
     * for a richer interview demo).
     */
    public List<OrderEntity> scatterGatherByAllStatuses() {
        long start = System.nanoTime();

        List<CompletableFuture<List<OrderEntity>>> futures = ALL_STATUSES.stream()
                .map(status -> CompletableFuture.supplyAsync(
                        () -> orderRepository.findByStatusOnly(status), scatterGatherExecutor))
                .toList();

        List<OrderEntity> merged = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList());

        log.info("Scatter-gather across {} status partitions returned {} docs in {} ms",
                ALL_STATUSES.size(), merged.size(), elapsedMs(start));
        return merged;
    }

    public BigDecimal totalSpendForCustomer(String customerId) {
        return orderRepository.aggregateTotalSpendForCustomer(customerId);
    }

    public List<java.util.Map<String, Object>> orderTotalsByStatus() {
        return orderRepository.aggregateOrderTotalsByStatus();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
