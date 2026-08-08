package id.co.nativeapp.restaurant.inventory.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.lang.Nullable;

/**
 * The {@code ingredient} aggregate (ADR 0046 phase 1) — a per-outlet raw-material catalog row
 * (bahan: bread, patty, sauce, vegetables) counted at an {@link IngredientStocktake} physical
 * count. Distinct from {@link id.co.nativeapp.restaurant.menu.domain.MenuItem}, whose stock remains
 * the sellable-portion / sold-out (86) gate; a menu item's stock is NOT depleted by an ingredient
 * count (no recipes/BOM yet — a later phase).
 *
 * <p>Unlike a menu item's optional {@code stockQuantity}, an ingredient's {@code stockQty} is
 * ALWAYS tracked (never null) — there is no "untracked ingredient" state (ADR 0046). Quantities are
 * integer, in the ingredient's own opaque {@code unit} (no server-side conversions; the console's
 * unit picker ships g / ml / pcs / pack only, so an integer count is always honest — the ArchUnit
 * {@code entitiesHaveNoDecimalMoneyFields} rule bans a {@code BigDecimal}/float field on any
 * {@code @Entity}, which would otherwise be tempting for a fractional quantity).
 *
 * <p>Cost is an OPTIONAL pair — {@code unitCostMinor} + {@code costCurrency} — validated both-or-
 * neither (mirrored from the V31 {@code CHECK}); {@code null} means the ingredient is counted
 * operationally with no ledger impact. Extends {@link Auditable} (rule 4), covered by the V31 RLS
 * policy (rule 5).
 */
@Entity
@Table(name = "ingredient")
public class Ingredient extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  /** Opaque display text (g / ml / pcs / pack on the console); no server-side unit conversion. */
  @Column(name = "unit", nullable = false, length = 16)
  private String unit;

  /** Current stock, ALWAYS tracked — unlike {@code MenuItem.stockQuantity} (ADR 0046). */
  @Column(name = "stock_qty", nullable = false)
  private int stockQty;

  /** The ingredient's unit cost in minor units; {@code null} when no cost has been recorded. */
  @Column(name = "unit_cost_minor")
  @Nullable private Long unitCostMinor;

  /**
   * ISO-4217 code of {@link #unitCostMinor}; {@code null} exactly when the cost is {@code null}.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cost_currency", length = 3)
  @Nullable private String costCurrency;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  protected Ingredient() {
    // for JPA
  }

  /**
   * Creates a new active ingredient with a freshly generated id and zero stock.
   *
   * @param businessId the owning outlet
   * @param name display name (non-blank)
   * @param unit display unit (non-blank; g / ml / pcs / pack on the console)
   * @param unitCostMinor optional unit cost in minor units, or {@code null} for no cost
   * @param costCurrency the ISO-4217 code of {@code unitCostMinor}; required exactly when {@code
   *     unitCostMinor} is non-null
   */
  public Ingredient(
      UUID businessId,
      String name,
      String unit,
      @Nullable Long unitCostMinor,
      @Nullable String costCurrency) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.name = Objects.requireNonNull(name, "name");
    this.unit = Objects.requireNonNull(unit, "unit");
    this.stockQty = 0;
    this.active = true;
    setUnitCost(unitCostMinor, costCurrency);
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

  public String getUnit() {
    return unit;
  }

  public int getStockQty() {
    return stockQty;
  }

  /** The unit cost in minor units; {@code null} when no cost has been recorded. */
  @Nullable public Long getUnitCostMinor() {
    return unitCostMinor;
  }

  /** ISO-4217 code; {@code CHAR(3)} padding is stripped for callers. {@code null} when no cost. */
  @Nullable public String getCostCurrency() {
    return costCurrency == null ? null : costCurrency.strip();
  }

  public boolean isActive() {
    return active;
  }

  /**
   * Applies a partial update (PATCH semantics): {@code name}/{@code unit} null = leave unchanged.
   * {@code unitCostMinor} is applied only when non-null (mirrors {@code MenuItem#update} — there is
   * no "clear the cost" affordance on this endpoint); when non-null it requires a non-null {@code
   * costCurrency} in the same call (validated by {@link #setUnitCost}).
   *
   * @param name new display name; {@code null} = leave unchanged
   * @param unit new display unit; {@code null} = leave unchanged
   * @param unitCostMinor new unit cost; {@code null} = leave the cost unchanged
   * @param costCurrency the ISO-4217 code paired with {@code unitCostMinor} (required when it is
   *     non-null)
   */
  public void update(
      @Nullable String name,
      @Nullable String unit,
      @Nullable Long unitCostMinor,
      @Nullable String costCurrency) {
    if (name != null) {
      this.name = name;
    }
    if (unit != null) {
      this.unit = unit;
    }
    if (unitCostMinor != null) {
      setUnitCost(unitCostMinor, costCurrency);
    }
  }

  /** Deactivates this ingredient — it disappears from the active catalog read. */
  public void deactivate() {
    this.active = false;
  }

  /**
   * Sets the unit cost pair, validating the both-or-neither invariant the V31 {@code CHECK}
   * enforces and the ISO-4217 currency code via {@link Money#ofMinor(long, String)} (the returned
   * value is discarded — this entity persists the raw pair, matching {@code
   * menu_item.unit_cost_minor}).
   *
   * @throws IllegalArgumentException if exactly one of the pair is present, the amount is negative,
   *     or the currency code is not a valid ISO-4217 code
   */
  public void setUnitCost(@Nullable Long unitCostMinor, @Nullable String costCurrency) {
    boolean amountPresent = unitCostMinor != null;
    boolean currencyPresent = costCurrency != null && !costCurrency.isBlank();
    if (amountPresent != currencyPresent) {
      throw new IllegalArgumentException(
          "unitCostMinor and costCurrency must both be present or both absent");
    }
    if (amountPresent) {
      if (unitCostMinor < 0) {
        throw new IllegalArgumentException("unitCostMinor must be >= 0, got: " + unitCostMinor);
      }
      // Re-validates the ISO-4217 code (Currency.getInstance throws IllegalArgumentException for an
      // unknown code); the Money instance itself is discarded (rule 8 — the raw pair is persisted).
      Money.ofMinor(unitCostMinor, costCurrency);
    }
    this.unitCostMinor = unitCostMinor;
    // Normalized: a blank currency counts as ABSENT above, so persist null — storing the blank
    // would slip a currency-without-amount row past the guard into the V31 CHECK (a 500).
    this.costCurrency = currencyPresent ? costCurrency : null;
  }

  // ---------------------------------------------------------------------------
  // Stock tracking — ALWAYS tracked (never null), unlike MenuItem (ADR 0046).
  // ---------------------------------------------------------------------------

  /**
   * Sets the absolute stock quantity.
   *
   * @param qty new quantity (&ge; 0)
   * @throws IllegalArgumentException if {@code qty} is negative
   */
  public void setStock(int qty) {
    if (qty < 0) {
      throw new IllegalArgumentException("stock_qty must be >= 0, got: " + qty);
    }
    this.stockQty = qty;
  }

  /**
   * Adds {@code delta} units to the current stock, flooring at 0 ({@code MenuItem#addStock}'s
   * floor-0 logic, minus the untracked branch — an ingredient has no untracked state, ADR 0046).
   *
   * @param delta units to add (positive to restock, negative to manually remove; floored at 0)
   */
  public void addStock(int delta) {
    int next = stockQty + delta;
    this.stockQty = Math.max(0, next);
  }
}
