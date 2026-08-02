package id.co.nativeapp.employee.payroll.dto;

import id.co.nativeapp.employee.payroll.domain.RuleProvenance;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Request body for {@code PATCH /api/v1/payroll-setup/rules/{ruleKey}} — the human-verification
 * override path (the TER activation checklist, ADR 0031). Creates a NEW effective-dated row
 * starting {@code effectiveFrom}, superseding the rule_key's currently-open row; the calc FAMILY
 * (the linked rule's {@code calc_type}) is fixed by the existing row and cannot change here — only
 * its figures, effective date, source note, and provenance (the {@code ILLUSTRATIVE_PLACEHOLDER ->
 * OFFICIAL} flip). {@code paramsJson} is validated through the SAME {@link
 * id.co.nativeapp.employee.payroll.domain.StatutoryParams} family parser the dataset loader uses
 * before anything is written.
 *
 * @param paramsJson the new rule's params, verbatim JSON matching the existing calc family's shape
 * @param effectiveFrom must be strictly after the current row's {@code effectiveFrom}
 * @param sourceNote the regulation citation / verification note for this override
 * @param provenance {@code ILLUSTRATIVE_PLACEHOLDER} or {@code OFFICIAL} for the new row
 */
public record OverrideStatutoryRuleRequest(
    @NotBlank String paramsJson,
    @NotNull LocalDate effectiveFrom,
    @NotBlank String sourceNote,
    @NotNull RuleProvenance provenance) {}
