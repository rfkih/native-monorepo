package id.co.nativeapp.barbershop.catalog.dto;

import java.util.UUID;

/**
 * Response body for a {@code service_item} or {@code service_addon} row. {@code durationMinutes} is
 * always {@code null} for an addon (the column exists only on {@code service_item}, RESERVED for a
 * future appointments app; read-only here).
 */
public record CatalogItemResponse(
    UUID id,
    UUID businessId,
    String name,
    String description,
    long priceMinor,
    String currency,
    boolean active,
    int displayOrder,
    Integer durationMinutes) {}
