package id.co.nativeapp.barbershop.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

/**
 * Request body to create a {@code service_item} or {@code service_addon} row. {@code company_id}
 * is intentionally absent — it comes from the bound tenant scope, never the client (rule 5).
 *
 * <p>{@code priceMinor} is {@link PositiveOrZero} — a catalog item may legitimately be priced at
 * zero (a free add-on / promotional line), so {@code Positive} would be wrong here (unlike a
 * recorded ticket's grand total, which must be strictly positive revenue).
 *
 * <p>{@code durationMinutes} applies ONLY to {@code service_item} (RESERVED for a future
 * appointments app); it is accepted here so the two catalog endpoints share one create shape (the
 * carwash-service precedent), but is silently ignored by the addon write path — {@code
 * service_addon} has no such column.
 *
 * @param businessId the barbershop outlet (org_unit) this item is offered at
 * @param name the display name
 * @param description the optional description; {@code null} for none
 * @param priceMinor the price in the currency's minor units (never a float); must be &ge; 0
 * @param currency the ISO-4217 currency code (uppercase, 3 letters)
 * @param durationMinutes the optional typical duration in minutes ({@code service_item} only);
 *     {@code null} for unset; must be &gt; 0 when present
 */
public record CatalogItemCreateRequest(
    @NotNull UUID businessId,
    @NotBlank String name,
    String description,
    @NotNull @PositiveOrZero Long priceMinor,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an uppercase ISO-4217 code") String currency,
    @Positive Integer durationMinutes) {}
