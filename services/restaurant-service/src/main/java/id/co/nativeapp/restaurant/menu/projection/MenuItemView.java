package id.co.nativeapp.restaurant.menu.projection;

import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Read projection over the {@code menu_item} row — only the columns a response needs, never the
 * {@link id.co.nativeapp.tenant.Auditable Auditable} bookkeeping.
 *
 * <p>Backs the native read queries on {@code MenuItemRepository} (e.g. {@code
 * findActiveByBusiness}). Snake_case native-query aliases map to these accessors via Spring Data's
 * projection-interface convention (CLAUDE.md "native-query aliases snake_case; map via projection
 * interfaces"). Lives in its own {@code projection} package — a read model is neither the
 * write-side {@code domain} entity nor a request/response {@code dto}.
 */
public interface MenuItemView {

  UUID getId();

  UUID getBusinessId();

  String getName();

  String getCategory();

  UUID getCategoryId();

  long getPriceMinor();

  String getCurrency();

  boolean isActive();

  boolean isAvailable();

  /** Current stock quantity; {@code null} when the item is untracked (infinite supply). */
  @Nullable Integer getStockQuantity();

  /**
   * LEGACY image URL (base64 data URL or external HTTP(S) URL, pre-ADR-0048); {@code null} when no
   * legacy image is set (migrated rows carry {@link #getImageKey()} instead). Only the menu-list
   * query selects it — the checkout-validation query omits both image columns, so this accessor is
   * {@code null} on that path.
   */
  @Nullable String getImageUrl();

  /**
   * Object-store image key (ADR 0048); {@code null} when the item has no stored image. The reader
   * maps this to the public {@code /api/media/…} URL — the raw key never reaches a response. Only
   * the menu-list query selects it (see {@link #getImageUrl()}).
   */
  @Nullable String getImageKey();

  /**
   * The item's unit cost in minor units (same currency as {@link #getCurrency()}); {@code null}
   * when no cost has been recorded (ADR 0038 phase 3 — stocktake variance valuation).
   */
  @Nullable Long getUnitCostMinor();
}
