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

  /**
   * The BASE unit stock is stored and costed in (g / ml / pcs / pack). Opaque to the server — no
   * server-side unit conversion; {@link #stockQty} and all cost math are in this unit.
   */
  @Column(name = "unit", nullable = false, length = 16)
  private String unit;

  /**
   * The unit SHOWN to the user when it differs from the stored base {@link #unit} — {@code kg} over
   * a base of {@code g}, {@code liter} over {@code ml} (factor 1000). {@code null} when the display
   * unit IS the base unit (g / ml / pcs / pack). A pure display LABEL: the server never converts —
   * the console divides/multiplies by the factor so a weight can be entered as 1.5 kg yet stored as
   * 1500 g, keeping quantities whole integers (ArchUnit HR-8 forbids decimal entity fields).
   */
  @Column(name = "display_unit", length = 16)
  @Nullable private String displayUnit;

  /**
   * How many BASE units one purchase pack usually holds ("1 kemasan tortilla = 20 pcs"), or {@code
   * null} when the item is simply bought in its own unit.
   *
   * <p>Deliberately a DEFAULT, not a rule: pack sizes differ per brand and per supplier, so a
   * purchase line pre-fills from this and stays free to override it. Nothing derives stock from
   * this column — the purchase sends the already-multiplied base quantity — so a stale or wrong
   * value can never move stock on its own.
   *
   * <p>Distinct from {@link #displayUnit}, which is the fixed 1000x kg/g · liter/ml family (V37).
   */
  @Column(name = "pack_size")
  @Nullable private Integer packSize;

  /**
   * Current stock in the base {@link #unit}, ALWAYS tracked — unlike {@code MenuItem.stockQuantity}
   * (ADR 0046).
   */
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

  /**
   * Total acquisition value of the stock on hand, in {@link #costCurrency}'s minor units — the
   * SOURCE OF TRUTH for the perpetual moving weighted-average cost (V36). {@link #unitCostMinor} is
   * DERIVED from this ({@code round(value / qty)}); keeping the value here preserves the exact
   * fractional average that a rounded per-unit cost would lose (e.g. Rp 12,75/g). Always {@code 0}
   * when the ingredient is uncosted or holds no stock (the V36 CHECK invariants).
   */
  @Column(name = "stock_value_minor", nullable = false)
  private long stockValueMinor;

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

  @Nullable public String getDisplayUnit() {
    return displayUnit;
  }

  /**
   * Sets the display-unit label (see the field javadoc); {@code null} = display in the base unit.
   */
  public void setDisplayUnit(@Nullable String displayUnit) {
    this.displayUnit = displayUnit;
  }

  /** The default pack contents in base units, or {@code null} when the item has no pack. */
  @Nullable public Integer getPackSize() {
    return packSize;
  }

  /**
   * Sets the default pack contents. A non-positive value is rejected rather than silently
   * normalised: it would pre-fill a purchase line with a quantity multiplier of zero or negative.
   */
  public void setPackSize(@Nullable Integer packSize) {
    if (packSize != null && packSize <= 0) {
      throw new IllegalArgumentException("packSize must be strictly positive, got: " + packSize);
    }
    this.packSize = packSize;
  }

  public int getStockQty() {
    return stockQty;
  }

  /**
   * Total acquisition value of stock on hand, in {@link #costCurrency}'s minor units (V36) — the
   * source of truth from which {@link #getUnitCostMinor()} is derived. {@code 0} when uncosted or
   * empty.
   */
  public long getStockValueMinor() {
    return stockValueMinor;
  }

  /**
   * The DERIVED unit-cost display cache in minor units ({@code round(value / qty)}); {@code null}
   * when no cost has ever been recorded. On a costed but empty ingredient this retains the
   * last-known unit cost. Read paths (list, HPP) use this; the authoritative value is {@link
   * #getStockValueMinor()} + {@link #getStockQty()}.
   */
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
  @SuppressWarnings("checkstyle:ParameterNumber")
  public void update(
      @Nullable String name,
      @Nullable String unit,
      @Nullable String displayUnit,
      @Nullable Long unitCostMinor,
      @Nullable String costCurrency) {
    update(name, unit, displayUnit, unitCostMinor, costCurrency, null, false);
  }

  /**
   * As {@link #update}, plus the default pack size. {@code clearPackSize} distinguishes "leave it
   * alone" (the PATCH omitted the field) from "remove the default" — a null value alone cannot say
   * which was meant.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public void update(
      @Nullable String name,
      @Nullable String unit,
      @Nullable String displayUnit,
      @Nullable Long unitCostMinor,
      @Nullable String costCurrency,
      @Nullable Integer packSize,
      boolean clearPackSize) {
    // Validate the unit change BEFORE any mutation, so a refusal cannot leave a half-applied edit
    // even outside a transaction.
    boolean baseUnitChanges = unit != null && !unit.equals(this.unit);
    if (baseUnitChanges && (stockQty != 0 || stockValueMinor != 0)) {
      throw new IngredientUnitChangeException(
          "cannot change the unit of '"
              + this.name
              + "' from "
              + this.unit
              + " to "
              + unit
              + " while it still holds stock ("
              + stockQty
              + " "
              + this.unit
              + "): set the stock to zero first, then change the unit and re-enter the"
              + " quantity in the new unit");
    }

    if (clearPackSize) {
      setPackSize(null);
    } else if (packSize != null) {
      setPackSize(packSize);
    }
    if (name != null) {
      this.name = name;
    }
    if (baseUnitChanges) {
      // No ratio relates a count to a weight, so a base-unit change cannot be converted -- it
      // would silently reinterpret stock_qty. Stock must already be zero (guarded above).
      //
      // Zero stock is NOT sufficient on its own: unit_cost_minor is a PER-BASE-UNIT figure that
      // survives the zeroing (recomputeUnitCostCache is a no-op at qty 0, by design), and
      // applyCostlessQuantity's from-empty branch re-values new stock at it. Left behind, "Rp
      // 10.000 per pcs" silently becomes "per gram" and the next "Atur jumlah" of 2 kg books Rp
      // 20.000.000 -- the exact 1000x poisoning this guard exists to prevent, one step later.
      // pack_size is likewise stored in BASE units. So every per-unit figure hanging off the old
      // unit is reset here and must be re-entered; a priced receive re-establishes the cost.
      this.unit = unit;
      setUnitCost(null, null);
      setPackSize(null);
    }
    if (displayUnit != null) {
      // A blank string is the caller's explicit "clear the display unit" (back to a base unit);
      // a null leaves it untouched (partial PATCH), matching the other fields here.
      this.displayUnit = displayUnit.isBlank() ? null : displayUnit;
    }
    if (unitCostMinor != null) {
      revalue(unitCostMinor, costCurrency);
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

  /**
   * Manually (re)sets the unit cost — a PATCH override / correction, NOT a moving-average blend.
   * Revalues the CURRENT stock at the given unit cost: {@code stock_value_minor = qty × unitCost}
   * (so {@link #getUnitCostMinor()} then reads back exactly {@code unitCostMinor}). Validated
   * both-or-neither via {@link #setUnitCost}.
   *
   * @throws IllegalArgumentException per {@link #setUnitCost} (negative amount / bad currency /
   *     currency without amount)
   */
  public void revalue(long newUnitCostMinor, @Nullable String currency) {
    setUnitCost(newUnitCostMinor, currency);
    // qty × unitCost — exact; value follows the manually-set unit cost.
    this.stockValueMinor = Math.multiplyExact((long) stockQty, newUnitCostMinor);
  }

  // ---------------------------------------------------------------------------
  // Stock tracking — ALWAYS tracked (never null), unlike MenuItem (ADR 0046).
  // ---------------------------------------------------------------------------

  /**
   * Receives a purchased quantity at its actual paid price — the moving weighted-average update
   * (V36). Adds the EXACT {@code amountPaidMinor} to the value bucket and {@code addedQty} to
   * stock; the derived unit cost ({@link #getUnitCostMinor()}) re-blends. Capturing the TOTAL paid
   * for the receipt (never a per-unit price) is what keeps the average exact — a Rp 12,75/unit
   * blend is never rounded away.
   *
   * <p>On a previously UNCOSTED ingredient this establishes the cost currency and values the WHOLE
   * resulting stock at this receipt's unit price (pre-existing units are assumed acquired at the
   * same price). On a costed ingredient the receipt currency must match its cost currency (no
   * implicit FX — finance owns FX).
   *
   * @param addedQty units received (strictly positive)
   * @param amountPaidMinor total paid for this receipt, in {@code currency}'s minor units (&ge; 0)
   * @param currency the ISO-4217 code the amount is paid in
   * @throws IllegalArgumentException if {@code addedQty <= 0}, {@code amountPaidMinor < 0}, the
   *     currency is not a valid ISO-4217 code, or it differs from an existing cost currency
   */
  public void receive(int addedQty, long amountPaidMinor, String currency) {
    if (addedQty <= 0) {
      throw new IllegalArgumentException("receive quantity must be positive, got: " + addedQty);
    }
    if (amountPaidMinor < 0) {
      throw new IllegalArgumentException("amountPaidMinor must be >= 0, got: " + amountPaidMinor);
    }
    Objects.requireNonNull(currency, "currency");
    Money paid = Money.ofMinor(amountPaidMinor, currency); // validates the ISO-4217 code

    if (costCurrency == null) {
      // Uncosted -> costed: value the WHOLE resulting stock at this receipt's unit price.
      long resultingQty = (long) stockQty + addedQty;
      this.costCurrency = currency;
      this.stockValueMinor = paid.mulDiv(resultingQty, addedQty).amountMinor();
      this.stockQty = Math.toIntExact(resultingQty);
    } else {
      if (!getCostCurrency().equals(currency)) {
        throw new IllegalArgumentException(
            "receipt currency "
                + currency
                + " does not match ingredient cost currency "
                + getCostCurrency());
      }
      this.stockValueMinor = Math.addExact(this.stockValueMinor, amountPaidMinor);
      this.stockQty = Math.addExact(this.stockQty, addedQty);
    }
    recomputeUnitCostCache();
  }

  // ---------------------------------------------------------------------------
  // Stock tracking — ALWAYS tracked (never null), unlike MenuItem (ADR 0046).
  // ---------------------------------------------------------------------------

  /**
   * Sets the absolute stock quantity — a COSTLESS adjustment (a physical re-count / correction, not
   * a purchase). Preserves the unit cost: the value bucket scales with quantity (V36).
   *
   * @param qty new quantity (&ge; 0)
   * @throws IllegalArgumentException if {@code qty} is negative
   */
  public void setStock(int qty) {
    if (qty < 0) {
      throw new IllegalArgumentException("stock_qty must be >= 0, got: " + qty);
    }
    applyCostlessQuantity(qty);
  }

  /**
   * Adds {@code delta} units to the current stock, flooring at 0 ({@code MenuItem#addStock}'s
   * floor-0 logic, minus the untracked branch — an ingredient has no untracked state, ADR 0046). A
   * COSTLESS adjustment (found/lost stock, not a priced purchase — that is {@link #receive}):
   * preserves the unit cost by scaling the value bucket with quantity (V36).
   *
   * @param delta units to add (positive to restock, negative to manually remove; floored at 0)
   */
  public void addStock(int delta) {
    // Add in long then floor at 0 — mirrors the *Exact discipline of the other paths: an extreme
    // positive delta throws (toIntExact) rather than silently wrapping negative.
    applyCostlessQuantity(Math.toIntExact(Math.max(0L, (long) stockQty + delta)));
  }

  /**
   * Applies a COSTLESS quantity change to {@code newQty} — a set/add/stocktake adjustment that must
   * NOT move the unit cost. The value bucket scales with quantity so {@code value / qty} (the
   * average unit cost) is preserved: a re-count or a sale never changes what a unit cost. An
   * uncosted ingredient carries no value. Scaling up from EMPTY stock values the new units at the
   * last-known unit cost ({@link #unitCostMinor}), since there is no ratio to scale.
   */
  private void applyCostlessQuantity(int newQty) {
    if (costCurrency == null) {
      // Uncosted: no valuation bucket (V36 invariant: value = 0 when cost_currency IS NULL).
      this.stockQty = newQty;
      this.stockValueMinor = 0L;
      return;
    }
    long newValue;
    if (stockQty > 0) {
      // Scale value with qty (preserves the average) — single HALF_EVEN rounding via mulDiv.
      newValue =
          Money.ofMinor(stockValueMinor, costCurrency).mulDiv(newQty, stockQty).amountMinor();
    } else {
      // From empty: value the new units at the last-known unit cost (a costed ingredient always
      // retains a non-null unitCostMinor cache — setUnitCost enforces both-or-neither).
      newValue = unitCostMinor == null ? 0L : Math.multiplyExact((long) newQty, unitCostMinor);
    }
    this.stockQty = newQty;
    this.stockValueMinor = newValue;
    recomputeUnitCostCache();
  }

  /**
   * Recomputes the derived unit-cost display cache {@code = round(value / qty)} (HALF_EVEN, via the
   * shared {@link Money#mulDiv} rounding primitive). Left UNCHANGED when stock is zero (retains the
   * last-known unit cost) or the ingredient is uncosted.
   */
  private void recomputeUnitCostCache() {
    if (stockQty > 0 && costCurrency != null) {
      this.unitCostMinor =
          Money.ofMinor(stockValueMinor, costCurrency).mulDiv(1L, stockQty).amountMinor();
    }
  }
}
