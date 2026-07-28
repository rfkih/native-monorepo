package id.co.nativeapp.finance.ar.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body to update a customer. {@code name} is required; {@code active} is optional (null
 * leaves the flag unchanged).
 */
public record UpdateCustomerRequest(
    @NotBlank @Size(max = 255) String name,
    @Email @Size(max = 320) String email,
    @Size(max = 64) String taxId,
    Boolean active) {}
