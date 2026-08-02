package id.co.nativeapp.employee.payroll.dto;

import java.time.LocalDate;

/**
 * One row of {@code GET /api/v1/payroll-setup/rules} — the console's rules table. Deliberately
 * carries NO {@code paramsJson} (the list view is identity/provenance/effective-range only; the
 * figures are a separate detail fetch, {@link StatutoryRuleDetailResponse}).
 *
 * @param provenance {@code ILLUSTRATIVE_PLACEHOLDER} or {@code OFFICIAL} — the console's badge
 * @param effectiveTo the {@code 9999-12-31} sentinel for a still-open row (never null)
 * @param active whether this row is the one that actually resolves (see {@link
 *     id.co.nativeapp.employee.payroll.domain.StatutoryRule#supersede})
 */
public record StatutoryRuleResponse(
    String ruleKey,
    String ruleVersion,
    String calcType,
    String provenance,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String sourceNote,
    boolean active) {}
