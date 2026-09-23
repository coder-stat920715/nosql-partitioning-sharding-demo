package com.publicissapient.partitioning.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class TenantRouteRequest {

    @NotBlank
    private String tenantRegion; // US, EU, APAC

    @NotBlank
    private String tenantId;

    @NotBlank
    private String dataKey;

    @NotBlank
    private String dataValue;

    private List<String> tags;
}
