package id.co.nativeapp.barbershop.catalog.dto;

import java.util.UUID;

/** Response body for a {@code staff_profile} row. */
public record StaffProfileResponse(
    UUID id, UUID businessId, String displayLabel, UUID employeeId, boolean active) {}
