package id.co.nativeapp.finance.bank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body to create a bank account. {@code name} and {@code currency} are required; {@code
 * accountNumber} is optional (a business-contact-class field, never logged). {@code currency} is
 * set once at creation and is immutable thereafter (mirrors the company base-currency invariant).
 */
public record CreateBankAccountRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 64) String accountNumber,
    @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO-4217 code") String currency) {}
