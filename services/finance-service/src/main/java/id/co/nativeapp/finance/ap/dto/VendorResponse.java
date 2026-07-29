package id.co.nativeapp.finance.ap.dto;

import java.util.UUID;

/** API response for a vendor. */
public record VendorResponse(UUID id, String name, String email, String taxId, boolean active) {}
