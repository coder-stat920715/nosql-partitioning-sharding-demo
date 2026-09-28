package com.souptik.partitioning.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PlaceOrderRequest {

    @NotBlank
    private String customerId;

    @NotNull
    @DecimalMin(value = "0.01")
    private BigDecimal orderAmount;

    @NotBlank
    private String region;
}
