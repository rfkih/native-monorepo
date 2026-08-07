package id.co.nativeapp.finance.bank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Request body to reconcile a statement line. {@code category} must be one of {@code CLEARING} /
 * {@code BANK_FEE} / {@code INTEREST} / {@code QRIS_CLEARING}; whether it is a valid pairing for
 * the line's direction (deposit vs withdrawal) is validated by {@code ReconciliationWriter}, not
 * here.
 *
 * <p>{@code feeMinor} is the OPTIONAL acquirer MDR fee (ADR 0045) — {@code null} (the default)
 * means "no fee". Valid ONLY when {@code category} is {@code QRIS_CLEARING}; a non-null value
 * paired with any other category is rejected by {@code ReconciliationWriter} (the same 400 shape as
 * a category/direction mismatch). A negative value is rejected here AND, redundantly, by {@code
 * ReconciliationWriter} for callers that build a posting without going through this DTO.
 */
public record ReconcileRequest(
    @NotBlank @Pattern(
            regexp = "CLEARING|BANK_FEE|INTEREST|QRIS_CLEARING",
            message = "category must be CLEARING, BANK_FEE, INTEREST, or QRIS_CLEARING")
        String category,
    @PositiveOrZero(message = "feeMinor must not be negative") Long feeMinor) {}
