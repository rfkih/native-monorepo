package id.co.nativeapp.employee.payroll.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Request body for {@code POST /api/v1/employees/{eid}/compensation/{pkgId}/commission} — set an
 * own-sales commission on a compensation package. The rate is basis points (500 = 5%), capped at
 * 100% (10000 bp). {@code metricKey} defaults to {@code sales_amount} (the restaurant sales
 * metric).
 *
 * @param percentBasisPoints the commission rate in basis points (1..10000)
 * @param metricKey the sales metric the commission applies to
 */
public record CreateCommissionRequest(
    @NotNull @Positive @Max(10_000) Integer percentBasisPoints,
    @NotBlank @Pattern(regexp = "[a-z_]{1,64}") String metricKey) {}
