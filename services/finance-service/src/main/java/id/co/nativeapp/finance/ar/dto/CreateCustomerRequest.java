package id.co.nativeapp.finance.ar.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body to create a customer. {@code name} is required; {@code email} / {@code taxId} are
 * optional business-contact fields (validated for shape, never logged).
 */
public record CreateCustomerRequest(
    @NotBlank @Size(max = 255) String name,
    @Email @Size(max = 320) String email,
    @Size(max = 64) String taxId) {}
