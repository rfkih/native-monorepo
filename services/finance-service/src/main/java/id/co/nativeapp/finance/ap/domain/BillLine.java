package id.co.nativeapp.finance.ap.domain;

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

/**
 * One billed line of a {@link Bill}. {@code line_total = unit_price × quantity} is computed once on
 * the server (never a float — {@link Money#multiply(long)} is exact) and stored, so the bill total
 * is authoritative and immutable to client tampering.
 *
 * <p>Quantity is a whole unit in Phase 2 (fractional quantities are a later enhancement). Money is
 * stored as {@code unit_price_minor} / {@code line_total_minor} (BIGINT) plus a single {@code
 * currency} column — the same "two longs + one currency" shape as {@code JournalLine} (a {@code
 * MoneyEmbeddable} per amount would clash on the {@code currency} column).
 *
 * <p>Extends {@link Auditable}; under the {@code bill_line} RLS policy (V27).
 *
 * <p><strong>ADR 0067 Phase B, §3.</strong> {@link #inventory} (V54 {@code is_inventory}, {@code
 * NOT NULL DEFAULT FALSE}) is the optional per-line flag steering {@code BillWriter}'s net split
 * between {@code EXPENSE_NET} (non-inventory lines, unchanged {@code Dr 5000}) and {@code
 * INVENTORY_NET} (inventory-flagged lines, {@code Dr 2050 GRNI}) — read ONLY when the owning
 * company is perpetual-active; otherwise ignored (every line routes to {@code EXPENSE}, today's
 * unchanged behaviour).
 */
@Entity
@Table(name = "bill_line")
public class BillLine extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "bill_id", nullable = false, updatable = false)
  private UUID billId;

  @Column(name = "line_no", nullable = false, updatable = false)
  private int lineNo;

  @Column(name = "description", nullable = false, length = 500)
  private String description;

  @Column(name = "quantity", nullable = false)
  private int quantity;

  @Column(name = "unit_price_minor", nullable = false)
  private long unitPriceMinor;

  @Column(name = "line_total_minor", nullable = false)
  private long lineTotalMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  /** See the class docs (ADR 0067 Phase B, §3). Defaults {@code false} (V54 column default). */
  @Column(name = "is_inventory", nullable = false, updatable = false)
  private boolean inventory;

  /**
   * ADR 0072 P4 — the restaurant ingredient this inventory line purchases (opaque cross-service
   * reference, V59). Non-null ONLY on an inventory-flagged line, always paired with {@code
   * ingredient_qty_base}; a posted bill carrying it rides the {@code InventoryPurchaseRecorded}
   * event ({@code line_id} = this row's id) so restaurant receives the stock.
   */
  @Column(name = "ingredient_id", updatable = false)
  private UUID ingredientId;

  /** Display-name snapshot for finance-side lists (finance may not join restaurant's DB). */
  @Column(name = "ingredient_name", updatable = false, length = 255)
  private String ingredientName;

  /** Quantity purchased, in the ingredient's BASE unit (integer — ADR 0046). */
  @Column(name = "ingredient_qty_base", updatable = false)
  private Long ingredientQtyBase;

  protected BillLine() {
    // for JPA
  }

  /**
   * Builds a line, computing {@code line_total = unitPrice × quantity}. Quantity and unit price
   * must both be strictly positive.
   *
   * @param billId the owning bill id
   * @param lineNo the 1-based ordering within the bill
   * @param description the line description (required)
   * @param quantity the whole-unit quantity (&gt; 0)
   * @param unitPrice the per-unit price as {@link Money} (positive; its currency is the line's)
   * @param inventory whether this line routes to inventory (ADR 0067 Phase B, §3) when the owning
   *     company is perpetual-active; ignored otherwise
   */
  public static BillLine of(
      UUID billId,
      int lineNo,
      String description,
      int quantity,
      Money unitPrice,
      boolean inventory) {
    Objects.requireNonNull(billId, "billId");
    Objects.requireNonNull(unitPrice, "unitPrice");
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be strictly positive: " + quantity);
    }
    if (!unitPrice.isPositive()) {
      throw new IllegalArgumentException("unit price must be strictly positive: " + unitPrice);
    }
    Money lineTotal = unitPrice.multiply(quantity);
    BillLine line = new BillLine();
    line.id = UUID.randomUUID();
    line.billId = billId;
    line.lineNo = lineNo;
    line.description = requireDescription(description);
    line.quantity = quantity;
    line.unitPriceMinor = unitPrice.amountMinor();
    line.lineTotalMinor = lineTotal.amountMinor();
    line.currency = unitPrice.currency().getCurrencyCode();
    line.inventory = inventory;
    return line;
  }

  /**
   * Builds an inventory line carrying its ingredient linkage (ADR 0072 P4). Delegates to {@link
   * #of} for the money math and invariants; the ingredient triple obeys the V59 CHECKs: linkage
   * only on an inventory-flagged line, id and qty together, qty strictly positive.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static BillLine ofIngredient(
      UUID billId,
      int lineNo,
      String description,
      int quantity,
      Money unitPrice,
      UUID ingredientId,
      String ingredientName,
      long ingredientQtyBase) {
    Objects.requireNonNull(ingredientId, "ingredientId");
    if (ingredientQtyBase <= 0) {
      throw new IllegalArgumentException(
          "ingredientQtyBase must be strictly positive: " + ingredientQtyBase);
    }
    BillLine line = of(billId, lineNo, description, quantity, unitPrice, true);
    line.ingredientId = ingredientId;
    line.ingredientName = ingredientName == null ? "" : ingredientName.strip();
    line.ingredientQtyBase = ingredientQtyBase;
    return line;
  }

  private static String requireDescription(String description) {
    Objects.requireNonNull(description, "description");
    String trimmed = description.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("line description must not be blank");
    }
    return trimmed;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBillId() {
    return billId;
  }

  public int getLineNo() {
    return lineNo;
  }

  public String getDescription() {
    return description;
  }

  public int getQuantity() {
    return quantity;
  }

  /** The per-unit price as a {@link Money} value. */
  public Money unitPrice() {
    return Money.ofMinor(unitPriceMinor, currency.strip());
  }

  /** The extended line total ({@code unitPrice × quantity}) as a {@link Money} value. */
  public Money lineTotal() {
    return Money.ofMinor(lineTotalMinor, currency.strip());
  }

  /** See the class docs (ADR 0067 Phase B, §3). */
  public UUID getIngredientId() {
    return ingredientId;
  }

  public String getIngredientName() {
    return ingredientName;
  }

  public Long getIngredientQtyBase() {
    return ingredientQtyBase;
  }

  public boolean isInventory() {
    return inventory;
  }
}
