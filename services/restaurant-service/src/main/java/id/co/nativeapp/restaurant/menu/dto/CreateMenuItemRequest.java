package id.co.nativeapp.restaurant.menu.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.lang.Nullable;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/menu}. The monetary price is {@code priceMinor} (integer
 * minor units) + ISO-4217 {@code currency} (never a float, rule 8). {@code company_id} is
 * intentionally absent — it is stamped from the bound {@link id.co.nativeapp.tenant.TenantContext
 * TenantContext}, never trusted from the client (rule 5).
 *
 * <p>{@code imageUrl} is optional. It holds either a compact base64 data URL (the frontend
 * downsizes the photo before submitting) or an external HTTP(S) URL. The 3 MB cap prevents
 * accidental full-resolution uploads; TEXT at the DB layer is unconstrained.
 */
public record CreateMenuItemRequest(
    @NotNull UUID businessId,
    @NotBlank String name,
    @NotBlank String category,
    @NotNull @Positive Long priceMinor,
    @NotBlank String currency,
    @Nullable @Size(max = 3_000_000) String imageUrl) {

  /**
   * Convenience constructor for callers that do not supply an image (imageUrl defaults to
   * {@code null}). Used by existing tests and non-image create flows.
   */
  public CreateMenuItemRequest(
      UUID businessId, String name, String category, Long priceMinor, String currency) {
    this(businessId, name, category, priceMinor, currency, null);
  }
}
