package com.souptik.partitioning.controller;

import com.souptik.partitioning.controller.dto.PlaceOrderRequest;
import com.souptik.partitioning.controller.dto.TenantRouteRequest;
import com.souptik.partitioning.domain.MultiTenantDataEntity;
import com.souptik.partitioning.domain.OrderEntity;
import com.souptik.partitioning.router.RoutingContext;
import com.souptik.partitioning.service.PartitionedOrderService;
import com.souptik.partitioning.service.QueryProfilingService;
import com.souptik.partitioning.service.TenantRoutingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST surface for the partitioning/sharding demo.
 *
 * Grouped endpoints:
 *   /orders/*   -> hash-sharded OrderEntity: targeted writes/reads, scatter-gather, explain
 *   /tenants/*  -> directory/list partitioning via DynamicPartitionRouter
 */
@RestController
@RequestMapping("/api/v1/partitioning")
@RequiredArgsConstructor
@Tag(name = "Partitioning & Sharding Demo")
public class PartitioningController {

    private final PartitionedOrderService partitionedOrderService;
    private final QueryProfilingService queryProfilingService;
    private final TenantRoutingService tenantRoutingService;

    // ---------------------------------------------------------------- orders

    @PostMapping("/orders")
    @Operation(summary = "Insert an order. customerId (the hashed shard key) determines routing.")
    public ResponseEntity<OrderEntity> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        OrderEntity order = partitionedOrderService.placeOrder(
                request.getCustomerId(), request.getOrderAmount(), request.getRegion());
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping("/orders/targeted")
    @Operation(summary = "TARGETED read: shard key (customerId) supplied -> single shard hit.")
    public ResponseEntity<List<OrderEntity>> targetedRead(@RequestParam String customerId) {
        return ResponseEntity.ok(partitionedOrderService.findOrdersForCustomer(customerId));
    }

    @GetMapping("/orders/scatter-gather")
    @Operation(summary = "SCATTER-GATHER read: no shard key -> cross-shard parallel scan across all order statuses.")
    public ResponseEntity<List<OrderEntity>> scatterGatherRead() {
        return ResponseEntity.ok(partitionedOrderService.scatterGatherByAllStatuses());
    }

    @GetMapping("/orders/explain")
    @Operation(summary = "Runs .explain(\"executionStats\") to show whether a query is targeted or scatter-gather.")
    public ResponseEntity<Map<String, Object>> explainOrdersQuery(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(queryProfilingService.explainOrdersQuery(customerId, status));
    }

    @GetMapping("/orders/aggregate/by-status")
    @Operation(summary = "Cluster-wide aggregation: order totals grouped by status (merge-sort across shards).")
    public ResponseEntity<List<Map<String, Object>>> aggregateByStatus() {
        return ResponseEntity.ok(partitionedOrderService.orderTotalsByStatus());
    }

    @GetMapping("/orders/aggregate/customer-spend")
    @Operation(summary = "Targeted aggregation: total spend for one customer (single-shard pipeline).")
    public ResponseEntity<BigDecimal> customerSpend(@RequestParam String customerId) {
        return ResponseEntity.ok(partitionedOrderService.totalSpendForCustomer(customerId));
    }

    // ---------------------------------------------------------------- tenants

    @PostMapping("/tenants/route")
    @Operation(summary = "Dynamic tenant collection router: writes to the physical collection resolved for tenantRegion.")
    public ResponseEntity<MultiTenantDataEntity> routeTenantWrite(
            @Valid @RequestBody TenantRouteRequest request,
            @RequestHeader(value = "X-Tenant-Region", required = false) String headerRegion) {
        try {
            // Demonstrates header-driven routing context, falling back to the
            // region explicitly present in the request body.
            RoutingContext.setRegion(headerRegion != null ? headerRegion : request.getTenantRegion());

            MultiTenantDataEntity saved = tenantRoutingService.writeTenantData(
                    request.getTenantRegion(), request.getTenantId(),
                    request.getDataKey(), request.getDataValue(), request.getTags());

            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } finally {
            RoutingContext.clear();
        }
    }

    @GetMapping("/tenants/{tenantRegion}/{tenantId}")
    @Operation(summary = "Targeted tenant read: routed directly to the tenant's regional collection.")
    public ResponseEntity<List<MultiTenantDataEntity>> readTenantData(
            @PathVariable String tenantRegion, @PathVariable String tenantId) {
        return ResponseEntity.ok(tenantRoutingService.readTenantData(tenantRegion, tenantId));
    }

    @GetMapping("/tenants/{tenantId}/global")
    @Operation(summary = "Application-level scatter-gather across every regional tenant partition.")
    public ResponseEntity<List<MultiTenantDataEntity>> readTenantDataGlobal(@PathVariable String tenantId) {
        return ResponseEntity.ok(tenantRoutingService.readAcrossAllRegions(tenantId));
    }
}
