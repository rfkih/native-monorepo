package id.co.nativeapp.employee.payroll.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A commission rule (a {@code PERCENT_OF_METRIC} earning rule). All fields are non-PII config — the
 * basis-point rate is echoed as its real value (unlike base pay, a commission percentage is not an
 * individual salary figure).
 *
 * @param id the earning-rule id
 * @param metricKey the sales metric the commission is a percentage of (e.g. {@code sales_amount})
 * @param percentBasisPoints the commission rate in basis points (500 = 5%)
 * @param effectiveFrom the rule's start
 * @param effectiveTo the rule's end ({@code 9999-12-31} = open)
 */
public record CommissionResponse(
    UUID id,
    String metricKey,
    int percentBasisPoints,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
