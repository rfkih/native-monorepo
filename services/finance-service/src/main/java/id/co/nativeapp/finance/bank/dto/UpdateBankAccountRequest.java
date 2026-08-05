package id.co.nativeapp.finance.bank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body to update a bank account. {@code name} is required; {@code accountNumber} is
 * optional (blank clears it); {@code active} is optional (null leaves the flag unchanged). {@code
 * currency} is immutable and not settable here.
 */
public record UpdateBankAccountRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 64) String bankName,
    @Size(max = 64) String accountNumber,
    Boolean active) {}
