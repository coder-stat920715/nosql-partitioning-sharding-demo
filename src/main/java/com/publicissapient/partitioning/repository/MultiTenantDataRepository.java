package com.publicissapient.partitioning.repository;

import com.publicissapient.partitioning.domain.MultiTenantDataEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MultiTenantDataRepository extends MongoRepository<MultiTenantDataEntity, String> {

    // Targeted: both parts of the shard key -> single shard.
    List<MultiTenantDataEntity> findByTenantRegionAndTenantId(String tenantRegion, String tenantId);

    // Directed scatter across only the chunks owning this region.
    List<MultiTenantDataEntity> findByTenantRegion(String tenantRegion);
}
