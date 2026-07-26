package id.co.nativeapp.restaurant.bill.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code bill} aggregate — a long-lived, table-bound, appendable guest tab.
 *
 * <p>A bill is DISTINCT from a PARKED order: it accumulates items over multiple rounds and is paid
 * later. Multiple independent OPEN bills may exist for the same table (one per guest). Paying a
 * bill finalises it: deducts stock and records the {@code SaleRecorded} outbox event in the same
 * transaction (rule 3). The {@code sale_id} is set only when the bill transitions to PAID.
 *
 * <p>Extends {@link Auditable} (rule 4); covered by the {@code bill_tenant_isolation} RLS policy
 * in {@code V11__bill_tabs.sql} (rule 5). Money is stored as {@code BIGINT} minor units + {@code
 * CHAR(3)} currency — never a float (rule 8).
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA; application code uses the public
 * constructor which validates invariants.
 */
@Entity
@Table(name = "bill")
public class Bill extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  /** Nullable: only set for dine-in bills that reference a physical table. */
  @Column(name = "table_id")
  private UUID tableId;

  /** Short label identifying the guest, e.g. "Guest A" or "Table 3 / Seat 2". */
  @Column(name = "guest_label", nullable = false, length = 128)
  private String guestLabel;

  /** OPEN | PAID | CANCELLED — enforced by the DB CHECK constraint. */
  @Column(name = "status", nullable = false, length = 16)
  private String status;

  /**
   * ISO-4217 currency code, CHAR(3). All lines on this bill share this currency (enforced at
   * append time — same as order/cart single-currency rule). {@link SqlTypes#CHAR} makes Hibernate's
   * {@code ddl-auto=validate} agree with the migrated {@code CHAR(3)} (PostgreSQL bpchar) column.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  /**
   * Optional order-level discount in minor units. Stored so pay can recompute the breakdown
   * without persisting it. {@code null} means no discount.
   */
  @Column(name = "discount_minor")
  private Long discountMinor;

  /** Set at pay time when the bill transitions to PAID. */
  @Column(name = "sale_id")
  private UUID saleId;

  @OneToMany(
      mappedBy = "bill",
      cascade = CascadeType.ALL,
      fetch = FetchType.LAZY,
      orphanRemoval = true)
  private List<BillLine> lines = new ArrayList<>();

  protected Bill() {
    // for JPA
  }

  /**
   * Opens a new bill in OPEN status.
   *
   * @param businessId the originating business unit
   * @param tableId nullable; the physical table (dine-in only)
   * @param guestLabel short label for the guest
   * @param currency ISO-4217 currency code for all items on this bill
   */
  public Bill(UUID businessId, UUID tableId, String guestLabel, String currency) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.tableId = tableId;
    this.guestLabel = Objects.requireNonNull(guestLabel, "guestLabel");
    if (guestLabel.isBlank()) {
      throw new IllegalArgumentException("guestLabel must not be blank");
    }
    this.currency = Objects.requireNonNull(currency, "currency");
    if (currency.isBlank()) {
      throw new IllegalArgumentException("currency must not be blank");
    }
    this.status = "OPEN";
  }

  /**
   * Adds a line to this bill. The line's {@code company_id} is set by the caller (must match the
   * bill's company_id so RLS applies).
   */
  public void addLine(BillLine line) {
    lines.add(line);
    line.setBill(this);
  }

  /**
   * Removes a line from this bill. The bill must be OPEN.
   *
   * @throws IllegalStateException if the bill is not OPEN
   */
  public void removeLine(BillLine line) {
    requireOpen("removeLine");
    lines.remove(line);
  }

  /**
   * Links the sale record to this bill and transitions to PAID.
   *
   * @throws IllegalStateException if the bill is already PAID
   */
  public void markPaid(UUID saleId) {
    if ("PAID".equals(this.status)) {
      throw new IllegalStateException(
          "Bill " + id + " is already PAID; markPaid may not be called twice.");
    }
    requireOpen("markPaid");
    this.saleId = Objects.requireNonNull(saleId, "saleId");
    this.status = "PAID";
  }

  /**
   * Cancels this bill (no sale, no stock change).
   *
   * @throws IllegalStateException if the bill is not OPEN
   */
  public void cancel() {
    requireOpen("cancel");
    this.status = "CANCELLED";
  }

  /** Sets the order-level fixed discount in minor units (null = no discount). */
  public void setDiscountMinor(Long discountMinor) {
    this.discountMinor = discountMinor;
  }

  /**
   * Sets the currency on first item append. Only valid when the current currency is "XXX"
   * (the placeholder set at open time before the first round of items). Called by the bill service
   * layer on the first {@code appendLines} call.
   *
   * @throws IllegalStateException if the bill's currency has already been set to a real currency
   */
  public void establishCurrency(String currencyCode) {
    if (!"XXX".equals(this.currency)) {
      throw new IllegalStateException(
          "Bill "
              + id
              + " currency is already established as "
              + this.currency
              + "; cannot change to "
              + currencyCode);
    }
    this.currency = Objects.requireNonNull(currencyCode, "currencyCode");
  }

  private void requireOpen(String operation) {
    if (!"OPEN".equals(this.status)) {
      throw new IllegalStateException(
          operation + " requires an OPEN bill; current status: " + status + " (bill id: " + id + ")");
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public UUID getTableId() {
    return tableId;
  }

  public String getGuestLabel() {
    return guestLabel;
  }

  public String getStatus() {
    return status;
  }

  public String getCurrency() {
    return currency;
  }

  public Long getDiscountMinor() {
    return discountMinor;
  }

  public UUID getSaleId() {
    return saleId;
  }

  public List<BillLine> getLines() {
    return Collections.unmodifiableList(lines);
  }

  /** Convenience: sum of all line totals in minor units. */
  public long getSubtotalMinor() {
    long total = 0L;
    for (BillLine line : lines) {
      total = Math.addExact(total, line.getLineTotalMinor());
    }
    return total;
  }
}
