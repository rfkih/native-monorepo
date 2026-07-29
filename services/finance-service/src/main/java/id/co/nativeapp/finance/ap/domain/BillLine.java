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
   */
  public static BillLine of(
      UUID billId, int lineNo, String description, int quantity, Money unitPrice) {
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
}
