package id.co.nativeapp.employee.payroll.dto;

import java.time.LocalDate;

/**
 * {@code GET /api/v1/payroll-setup/rules/{ruleKey}} and the {@code PATCH} response — the full
 * currently-open row INCLUDING {@code paramsJson}, the override dialog's source of truth. {@code
 * statutory_rule} carries no PII (rule figures, not employee data), so this is safe to return
 * verbatim.
 */
public record StatutoryRuleDetailResponse(
    String ruleKey,
    String ruleVersion,
    String calcType,
    String paramsJson,
    String currency,
    String provenance,
    String sourceNote,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
