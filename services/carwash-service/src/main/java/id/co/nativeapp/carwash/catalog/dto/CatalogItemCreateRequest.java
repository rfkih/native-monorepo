package id.co.nativeapp.carwash.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

/**
 * Request body to create a {@code wash_package} or {@code wash_addon} row. {@code company_id} is
 * intentionally absent — it comes from the bound tenant scope, never the client (rule 5).
 *
 * <p>{@code priceMinor} is {@link PositiveOrZero} — a catalog item may legitimately be priced at
 * zero (a free add-on / promotional line), so {@code Positive} would be wrong here (unlike a
 * recorded wash's amount, which must be strictly positive revenue).
 *
 * @param businessId the carwash outlet (org_unit) this item is offered at
 * @param name the display name
 * @param description the optional description; {@code null} for none
 * @param priceMinor the price in the currency's minor units (never a float); must be &ge; 0
 * @param currency the ISO-4217 currency code (uppercase, 3 letters)
 */
public record CatalogItemCreateRequest(
    @NotNull UUID businessId,
    @NotBlank String name,
    String description,
    @NotNull @PositiveOrZero Long priceMinor,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an uppercase ISO-4217 code")
        String currency) {}
