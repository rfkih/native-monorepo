package id.co.nativeapp.finance.companyexpense.domain;

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
 * One ingredient line of an {@link CompanyExpenseKind#INVENTORY} company expense (ADR 0072). The
 * line id doubles as {@code goods_receipt.idempotency_key} on the restaurant side and as {@code
 * line_id} on the {@code InventoryPurchaseRecorded} wire — the per-line replay anchor that makes a
 * redelivered or duplicated line unable to double-add stock.
 *
 * <p>{@code ingredient_id}/{@code ingredient_name} are opaque restaurant-service references
 * (finance may not join another service's DB — rule 1; the name is a display snapshot only). {@code
 * qty_base} is in the ingredient's BASE unit (integer, ADR 0046). Extends {@link Auditable}; under
 * the {@code company_expense_line} FORCE-RLS policy (V58).
 */
@Entity
@Table(name = "company_expense_line")
public class CompanyExpenseLine extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "expense_id", nullable = false, updatable = false)
  private UUID expenseId;

  @Column(name = "line_no", nullable = false, updatable = false)
  private int lineNo;

  @Column(name = "ingredient_id", nullable = false, updatable = false)
  private UUID ingredientId;

  @Column(name = "ingredient_name", nullable = false, updatable = false, length = 255)
  private String ingredientName;

  /**
   * What the RECEIPT calls this line, when the vendor's wording differs from the inventory item's
   * name (a nota saying "AYAM BROILER 1KG" against the ingredient "Ayam fillet"). Null when they
   * are the same — readers fall back to {@link #ingredientName}. Finance-side bookkeeping only: the
   * stock side is keyed on {@code ingredient_id}, never on either name.
   */
  @Column(name = "description", updatable = false, length = 500)
  private String description;

  @Column(name = "qty_base", nullable = false, updatable = false)
  private long qtyBase;

  @Column(name = "value_minor", nullable = false, updatable = false)
  private long valueMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  protected CompanyExpenseLine() {
    // for JPA
  }

  /** Creates a line; qty must be strictly positive, value non-negative (a bonus item is legal). */
  public static CompanyExpenseLine of(
      UUID expenseId,
      int lineNo,
      UUID ingredientId,
      String ingredientName,
      long qtyBase,
      Money value) {
    return of(expenseId, lineNo, ingredientId, ingredientName, qtyBase, value, null);
  }

  /**
   * As {@link #of}, plus the receipt wording for this line ({@code null} when it matches the
   * inventory item's name).
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static CompanyExpenseLine of(
      UUID expenseId,
      int lineNo,
      UUID ingredientId,
      String ingredientName,
      long qtyBase,
      Money value,
      String description) {
    Objects.requireNonNull(value, "value");
    if (qtyBase <= 0) {
      throw new IllegalArgumentException("line qty_base must be strictly positive: " + qtyBase);
    }
    if (value.amountMinor() < 0) {
      throw new IllegalArgumentException("line value must be non-negative: " + value);
    }
    CompanyExpenseLine line = new CompanyExpenseLine();
    line.id = UUID.randomUUID();
    line.expenseId = Objects.requireNonNull(expenseId, "expenseId");
    line.lineNo = lineNo;
    line.ingredientId = Objects.requireNonNull(ingredientId, "ingredientId");
    line.ingredientName = Objects.requireNonNull(ingredientName, "ingredientName");
    line.qtyBase = qtyBase;
    line.valueMinor = value.amountMinor();
    line.currency = value.currency().getCurrencyCode();
    line.description = normalizeDescription(description, line.ingredientName);
    return line;
  }

  /**
   * The receipt wording, or {@code null} when the line is named after the inventory item. Blank
   * input and a value equal to the ingredient name both normalise to {@code null}, so "differs"
   * stays a real signal rather than a duplicated string.
   */
  private static String normalizeDescription(String description, String ingredientName) {
    if (description == null) {
      return null;
    }
    String trimmed = description.strip();
    if (trimmed.isEmpty() || trimmed.equals(ingredientName)) {
      return null;
    }
    return trimmed;
  }

  public String getDescription() {
    return description;
  }

  public UUID getId() {
    return id;
  }

  public UUID getExpenseId() {
    return expenseId;
  }

  public int getLineNo() {
    return lineNo;
  }

  public UUID getIngredientId() {
    return ingredientId;
  }

  public String getIngredientName() {
    return ingredientName;
  }

  public long getQtyBase() {
    return qtyBase;
  }

  public long getValueMinor() {
    return valueMinor;
  }

  public String getCurrency() {
    return currency;
  }
}
