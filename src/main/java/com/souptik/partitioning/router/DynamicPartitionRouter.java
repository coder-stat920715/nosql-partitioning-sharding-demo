package com.souptik.partitioning.router;

import com.souptik.partitioning.domain.MultiTenantDataEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom dynamic data router demonstrating APPLICATION-LEVEL directory /
 * multi-collection partitioning, layered on top of database-level sharding.
 *
 * Rather than extending AbstractRoutingDataSource-style single-connection
 * switching (which is a JDBC/relational concept), this router works at the
 * MongoTemplate + collection-name level, which is the idiomatic equivalent
 * for MongoDB: it resolves a *physical collection name* for a given
 * tenant/region key at request time, then delegates to the shared
 * MongoTemplate (itself already talking to a sharded cluster through
 * mongos) to execute against that specific collection.
 *
 * This is the pattern used when regulatory / data-residency requirements
 * demand physically separate collections (sometimes even separate
 * databases or clusters) per region, in addition to -- not instead of --
 * MongoDB's own shard-key based distribution.
 *
 * Routing key resolution order:
 *   1. Explicit region parameter passed by the caller
 *   2. RoutingContext (populated from the X-Tenant-Region request header)
 *   3. "default" partition (tenant_data itself)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicPartitionRouter {

    private final MongoTemplate mongoTemplate;

    private static final Set<String> SUPPORTED_REGIONS = Set.of("US", "EU", "APAC");
    private static final String DEFAULT_COLLECTION = "tenant_data";

    /** region -> physical collection name */
    private final Map<String, String> collectionMap = new ConcurrentHashMap<>();

    {
        collectionMap.put("US", "tenant_data_us");
        collectionMap.put("EU", "tenant_data_eu");
        collectionMap.put("APAC", "tenant_data_apac");
    }

    /**
     * Resolves the physical collection for a given region, falling back to
     * the RoutingContext (set from request headers) and finally to the
     * shared default collection if no region is known.
     */
    public String resolveCollection(String explicitRegion) {
        String region = explicitRegion != null ? explicitRegion : RoutingContext.getRegion();
        if (region == null) {
            log.debug("No routing region supplied - using default collection [{}]", DEFAULT_COLLECTION);
            return DEFAULT_COLLECTION;
        }
        String normalized = region.toUpperCase();
        if (!SUPPORTED_REGIONS.contains(normalized)) {
            log.warn("Unsupported region [{}] requested - falling back to default collection", region);
            return DEFAULT_COLLECTION;
        }
        String collection = collectionMap.get(normalized);
        log.debug("Routing region [{}] -> physical collection [{}]", normalized, collection);
        return collection;
    }

    /** Routed write: insert into the physical collection resolved for this tenant's region. */
    public MultiTenantDataEntity routeInsert(MultiTenantDataEntity entity) {
        String collection = resolveCollection(entity.getTenantRegion());
        return mongoTemplate.insert(entity, collection);
    }

    /** Routed read: find by tenantId within the physical collection resolved for the region. */
    public List<MultiTenantDataEntity> routeFindByTenant(String region, String tenantId) {
        String collection = resolveCollection(region);
        Query query = Query.query(Criteria.where("tenantId").is(tenantId));
        return mongoTemplate.find(query, MultiTenantDataEntity.class, collection);
    }

    /**
     * Fan-out read across every regional partition (used when the caller
     * wants a global tenant view without knowing the tenant's home region).
     * This is application-level scatter-gather, complementary to Mongo's
     * own shard-level scatter-gather.
     */
    public List<MultiTenantDataEntity> routeFindAcrossAllPartitions(String tenantId) {
        return collectionMap.values().stream()
                .flatMap(collection -> mongoTemplate
                        .find(Query.query(Criteria.where("tenantId").is(tenantId)), MultiTenantDataEntity.class, collection)
                        .stream())
                .toList();
    }

    public Set<String> supportedRegions() {
        return SUPPORTED_REGIONS;
    }
}
