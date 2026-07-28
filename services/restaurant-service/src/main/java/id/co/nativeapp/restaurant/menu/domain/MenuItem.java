package id.co.nativeapp.restaurant.menu.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.sale.domain.MoneyEmbeddable;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * The {@code menu_item} aggregate — a priced offering on a business's menu.
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code menu_item} RLS policy in
 * {@code V2__pos.sql} (rule 5). The price is a {@link Money} persisted as {@code price_minor BIGINT
 * + currency CHAR(3)} via {@link MoneyEmbeddable} (rule 8 — never a float).
 *
 * <p>Two distinct boolean flags govern visibility/sellability:
 *
 * <ul>
 *   <li>{@code active} — the item is in the catalog (manageable by an admin). An inactive item
 *       never appears to cashiers.
 *   <li>{@code available} — the item is available <em>right now</em> (can be 86'd by a cashier
 *       without removing it from the catalog). Checked at checkout; a cashier can toggle it via the
 *       86-endpoint.
 * </ul>
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA; application code uses the public
 * constructor which validates its invariants.
 */
@Entity
@Table(name = "menu_item")
public class MenuItem extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "category", nullable = false, length = 64)
  private String category;

  /** FK to {@link MenuCategory}; nullable until back-filled or set at item creation. */
  @Column(name = "category_id", nullable = true)
  private UUID categoryId;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "amountMinor",
        column = @Column(name = "price_minor", nullable = false)),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "currency", nullable = false, length = 3))
  })
  private MoneyEmbeddable price;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  /**
   * Whether this item is available for ordering <em>right now</em>. Distinct from {@code active}:
   * an active item may be temporarily unavailable (86'd) without being removed from the catalog.
   */
  @Column(name = "available", nullable = false)
  private boolean available = true;

  /**
   * Per-item stock count in units. {@code NULL} means the item is <em>untracked</em> (infinite
   * supply) and sales never deduct from it — only the manual {@code available} flag governs
   * sellability. A non-null value &ge; 0 means the item is <em>tracked</em>: POS sales auto-deduct
   * and the item is effectively sold out when this reaches 0.
   */
  @Column(name = "stock_quantity")
  @Nullable private Integer stockQuantity;

  /**
   * Optional image — either a compact base64 data URL (e.g. {@code data:image/jpeg;base64,...}) or
   * an external HTTP(S) URL. {@code NULL} means no image has been set. The application enforces a 3
   * MB soft cap via {@code @Size} validation on the request DTO; this column is unconstrained at
   * the DB layer so the sentinel sits in the right tier.
   */
  @Column(name = "image_url", nullable = true)
  @Nullable private String imageUrl;

  protected MenuItem() {
    // for JPA
  }

  /**
   * Creates a new active, available menu item with a freshly generated id.
   *
   * @param businessId the owning business unit
   * @param name display name (non-blank)
   * @param category item category (non-blank)
   * @param price the price as {@link Money} (never a float, rule 8)
   */
  public MenuItem(UUID businessId, String name, String category, Money price) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.name = Objects.requireNonNull(name, "name");
    this.category = Objects.requireNonNull(category, "category");
    this.price = MoneyEmbeddable.of(Objects.requireNonNull(price, "price"));
    this.active = true;
    this.available = true;
  }

  /**
   * Creates a new active, available menu item with an image URL.
   *
   * @param businessId the owning business unit
   * @param name display name (non-blank)
   * @param category item category (non-blank)
   * @param price the price as {@link Money} (never a float, rule 8)
   * @param imageUrl optional image data URL or external URL; {@code null} = no image
   */
  public MenuItem(
      UUID businessId, String name, String category, Money price, @Nullable String imageUrl) {
    this(businessId, name, category, price);
    this.imageUrl = imageUrl;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getName() {
    return name;
  }

  public String getCategory() {
    return category;
  }

  public UUID getCategoryId() {
    return categoryId;
  }

  /** Links this item to the given category. */
  public void assignCategory(UUID categoryId) {
    this.categoryId = categoryId;
  }

  /** The item price as a {@link Money} value (reconstructed from its columns). */
  public Money getPrice() {
    return price.toMoney();
  }

  public boolean isActive() {
    return active;
  }

  /** Whether this item is currently available for ordering (not 86'd). */
  public boolean isAvailable() {
    return available;
  }

  /** The optional image URL (data URL or external URL); {@code null} when not set. */
  @Nullable public String getImageUrl() {
    return imageUrl;
  }

  /**
   * Sets (or clears) the image URL. Pass {@code null} to remove the image; an empty string is
   * stored as {@code null} (treated as "clear").
   *
   * @param imageUrl new image URL, or {@code null} / empty to clear
   */
  public void setImageUrl(@Nullable String imageUrl) {
    this.imageUrl = (imageUrl != null && imageUrl.isEmpty()) ? null : imageUrl;
  }

  /**
   * Applies a partial update to this item's mutable fields. Each parameter is applied only when
   * non-null; a present empty string for {@code imageUrl} clears the image (PATCH convention —
   * absent field = leave unchanged, empty string = clear image).
   *
   * @param name new display name (non-blank); {@code null} = leave unchanged
   * @param category new category (non-blank); {@code null} = leave unchanged
   * @param price new price; {@code null} = leave unchanged (currency cannot be changed)
   * @param imageUrl new image URL, or empty string to clear; {@code null} = leave unchanged
   */
  public void update(
      @Nullable String name,
      @Nullable String category,
      @Nullable Money price,
      @Nullable String imageUrl) {
    if (name != null) {
      this.name = name;
    }
    if (category != null) {
      this.category = category;
    }
    if (price != null) {
      this.price = MoneyEmbeddable.of(price);
    }
    if (imageUrl != null) {
      // empty string = clear the image; any other value = set
      this.imageUrl = imageUrl.isEmpty() ? null : imageUrl;
    }
  }

  /** Deactivates this item — it will no longer appear in active-item reads. */
  public void deactivate() {
    this.active = false;
  }

  /** Marks the item as unavailable for ordering right now (86). */
  public void markUnavailable() {
    this.available = false;
  }

  /** Marks the item as available for ordering again (un-86). */
  public void markAvailable() {
    this.available = true;
  }

  // ---------------------------------------------------------------------------
  // Stock tracking
  // ---------------------------------------------------------------------------

  /**
   * Returns the current stock quantity, or {@code null} if this item is untracked (infinite
   * supply).
   */
  @Nullable public Integer getStockQuantity() {
    return stockQuantity;
  }

  /** Returns {@code true} if this item has a finite tracked stock (stock_quantity IS NOT NULL). */
  public boolean isTracked() {
    return stockQuantity != null;
  }

  /**
   * Returns {@code true} if the item is orderable by a cashier: it must be active, available (not
   * 86'd), and — if tracked — have stock &gt; 0.
   *
   * <p>Effective orderability = active &amp;&amp; available &amp;&amp; (stockQuantity IS NULL OR
   * stockQuantity &gt; 0).
   */
  public boolean isOrderable() {
    return active && available && (stockQuantity == null || stockQuantity > 0);
  }

  /**
   * Sets the absolute stock quantity. Pass {@code null} to revert to infinite / untracked. A
   * non-null value must be &ge; 0.
   *
   * @param qty new quantity (&ge; 0), or {@code null} to untrack
   * @throws IllegalArgumentException if qty is non-null and negative
   */
  public void setStock(@Nullable Integer qty) {
    if (qty != null && qty < 0) {
      throw new IllegalArgumentException("stock_quantity must be >= 0, got: " + qty);
    }
    this.stockQuantity = qty;
  }

  /**
   * Adds {@code delta} units to the current tracked stock, flooring at 0 (negative deltas cannot
   * make stock negative). Throws {@link UntrackedStockException} if the item is untracked — the
   * caller must first set an absolute quantity via {@link #setStock(Integer)}.
   *
   * @param delta units to add (positive to restock, negative to manually remove; floored at 0)
   * @throws UntrackedStockException if this item is not tracked
   */
  public void addStock(int delta) {
    if (stockQuantity == null) {
      throw new UntrackedStockException(id);
    }
    int next = stockQuantity + delta;
    this.stockQuantity = Math.max(0, next);
  }

  /**
   * Deducts {@code qty} from tracked stock. No-op for untracked items. Throws {@link
   * InsufficientStockException} if the tracked stock would go below zero.
   *
   * <p>This method is <em>not</em> the concurrency-safe DB path — it is used for the domain-unit
   * layer only. The production write path uses an atomic SQL UPDATE in {@link
   * id.co.nativeapp.restaurant.menu.repository.MenuItemRepository}.
   *
   * @param qty units to deduct (&ge; 1)
   * @throws InsufficientStockException if tracked stock &lt; qty
   */
  public void deduct(int qty) {
    if (stockQuantity == null) {
      // Untracked — no-op.
      return;
    }
    if (stockQuantity < qty) {
      throw new InsufficientStockException(id, name, qty, stockQuantity);
    }
    this.stockQuantity = stockQuantity - qty;
  }
}
