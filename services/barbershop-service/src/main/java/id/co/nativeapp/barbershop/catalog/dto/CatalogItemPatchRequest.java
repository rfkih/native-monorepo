package id.co.nativeapp.barbershop.catalog.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Partial-update request body for a {@code service_item} or {@code service_addon} row. Every field
 * is OPTIONAL — a {@code null} field leaves that attribute unchanged (the {@code
 * UpdateCustomerRequest} convention already used by finance-service). {@code currency} cannot be
 * changed via PATCH (a catalog item does not switch currency after creation; re-create it instead).
 *
 * @param name the new name, or {@code null} to leave unchanged
 * @param description the new description, or {@code null} to leave unchanged
 * @param priceMinor the new price in minor units (never a float), or {@code null} to leave
 *     unchanged; must be &ge; 0 when present
 * @param active the new active flag, or {@code null} to leave unchanged
 * @param displayOrder the new display order, or {@code null} to leave unchanged
 * @param durationMinutes the new duration in minutes ({@code service_item} only; ignored for
 *     addons), or {@code null} to leave unchanged; must be &gt; 0 when present
 */
public record CatalogItemPatchRequest(
    String name,
    String description,
    @PositiveOrZero Long priceMinor,
    Boolean active,
    Integer displayOrder,
    @Positive Integer durationMinutes) {}
