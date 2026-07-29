package id.co.nativeapp.finance.bank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body to reconcile a statement line. {@code category} must be one of {@code CLEARING} /
 * {@code BANK_FEE} / {@code INTEREST}; whether it is a valid pairing for the line's direction
 * (deposit vs withdrawal) is validated by {@code ReconciliationWriter}, not here.
 */
public record ReconcileRequest(
    @NotBlank @Pattern(
            regexp = "CLEARING|BANK_FEE|INTEREST",
            message = "category must be CLEARING, BANK_FEE, or INTEREST")
        String category) {}
