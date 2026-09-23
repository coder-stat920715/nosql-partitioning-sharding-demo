package com.publicissapient.partitioning.service;

import com.publicissapient.partitioning.domain.MultiTenantDataEntity;
import com.publicissapient.partitioning.router.DynamicPartitionRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TenantRoutingService {

    private final DynamicPartitionRouter router;

    public MultiTenantDataEntity writeTenantData(String tenantRegion, String tenantId,
                                                  String dataKey, String dataValue, List<String> tags) {
        MultiTenantDataEntity entity = MultiTenantDataEntity.builder()
                .tenantRegion(tenantRegion)
                .tenantId(tenantId)
                .dataKey(dataKey)
                .dataValue(dataValue)
                .tags(tags)
                .createdAt(Instant.now())
                .build();
        return router.routeInsert(entity);
    }

    public List<MultiTenantDataEntity> readTenantData(String tenantRegion, String tenantId) {
        return router.routeFindByTenant(tenantRegion, tenantId);
    }

    public List<MultiTenantDataEntity> readAcrossAllRegions(String tenantId) {
        return router.routeFindAcrossAllPartitions(tenantId);
    }
}
