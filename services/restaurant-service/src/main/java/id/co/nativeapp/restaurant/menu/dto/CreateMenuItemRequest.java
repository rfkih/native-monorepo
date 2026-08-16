package id.co.nativeapp.restaurant.menu.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Request body for {@code POST /api/v1/menu}. The monetary price is {@code priceMinor} (integer
 * minor units) + ISO-4217 {@code currency} (never a float, rule 8). {@code company_id} is
 * intentionally absent — it is stamped from the bound {@link id.co.nativeapp.tenant.TenantContext
 * TenantContext}, never trusted from the client (rule 5).
 *
 * <p>{@code imageUrl} is optional. It holds either a compact base64 data URL (the frontend
 * downsizes the photo before submitting) or an external HTTP(S) URL. The 3 MB cap prevents
 * accidental full-resolution uploads; TEXT at the DB layer is unconstrained.
 *
 * <p>{@code unitCostMinor} is optional (ADR 0038 phase 3 — menu unit-cost plumbing): the item's
 * cost in minor units, in the same currency as {@code priceMinor}, used to value an inventory
 * stocktake's variance. {@code null} = no cost recorded (the item can still be counted at
 * stocktake; its variance simply posts no ledger entry).
 *
 * <p>{@code autoTrackStock} is optional ("Lacak stok", 2026-08-16): {@code true} 1:1-auto-links the
 * new item to a same-named ingredient right after creation, so its sales deplete stock from day
 * one. {@code null}/{@code false} = no link (the bulk sweep or the drawer 1-klik can link later).
 */
public record CreateMenuItemRequest(
    @NotNull UUID businessId,
    @NotBlank String name,
    @NotBlank String category,
    @NotNull @Positive Long priceMinor,
    @NotBlank String currency,
    @Nullable @Size(max = 3_000_000) String imageUrl,
    @Nullable @PositiveOrZero Long unitCostMinor,
    @Nullable Boolean autoTrackStock) {

  /**
   * Back-compat constructor for the pre-autoTrackStock canonical shape (autoTrackStock defaults to
   * {@code null}) — keeps every existing 7-arg caller source-compatible.
   */
  public CreateMenuItemRequest(
      UUID businessId,
      String name,
      String category,
      Long priceMinor,
      String currency,
      @Nullable String imageUrl,
      @Nullable Long unitCostMinor) {
    this(businessId, name, category, priceMinor, currency, imageUrl, unitCostMinor, null);
  }

  /**
   * Back-compat constructor for callers that supply an image but no unit cost (unitCostMinor
   * defaults to {@code null}).
   */
  public CreateMenuItemRequest(
      UUID businessId,
      String name,
      String category,
      Long priceMinor,
      String currency,
      @Nullable String imageUrl) {
    this(businessId, name, category, priceMinor, currency, imageUrl, null, null);
  }

  /**
   * Convenience constructor for callers that do not supply an image or a unit cost (both default to
   * {@code null}). Used by existing tests and non-image create flows.
   */
  public CreateMenuItemRequest(
      UUID businessId, String name, String category, Long priceMinor, String currency) {
    this(businessId, name, category, priceMinor, currency, null, null, null);
  }
}
