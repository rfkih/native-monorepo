package id.co.nativeapp.finance.ar.dto;

import java.util.UUID;

/** API response for a customer. */
public record CustomerResponse(UUID id, String name, String email, String taxId, boolean active) {}
