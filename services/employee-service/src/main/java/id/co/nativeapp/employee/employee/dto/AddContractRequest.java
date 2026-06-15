package id.co.nativeapp.employee.employee.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The add-employment-contract request body. Validated at the edge ({@code @Valid}); {@code
 * company_id} / actor come from {@link id.co.nativeapp.tenant.TenantContext} (rule 5), never the
 * body. Holds no PII and no Money (this is the records-only slice).
 *
 * @param employmentType the contract kind (e.g. {@code "PERMANENT"} / {@code "CONTRACT"})
 * @param legalEmployerId the legal employer this contract is under
 * @param effectiveFrom the date the contract becomes effective
 * @param effectiveTo the date it ends, or {@code null} for open-ended (the 9999-12-31 sentinel)
 */
public record AddContractRequest(
    @NotBlank String employmentType,
    @NotNull UUID legalEmployerId,
    @NotNull LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
