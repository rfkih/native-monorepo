package id.co.nativeapp.carwash.catalog.dto;

import java.util.UUID;

/** Response body for a {@code wash_package} or {@code wash_addon} row. */
public record CatalogItemResponse(
    UUID id,
    UUID businessId,
    String name,
    String description,
    long priceMinor,
    String currency,
    boolean active,
    int displayOrder) {}
