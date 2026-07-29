package id.co.nativeapp.finance.ap.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code bill} aggregate — an AP document billed by a {@link Vendor} (Phase 2 AP), the mirror
 * of {@code Invoice} (Phase 1 AR).
 *
 * <p><strong>Lifecycle (enforced here).</strong> {@code DRAFT → POSTED → PARTIALLY_PAID → PAID}, or
 * {@code DRAFT | POSTED → VOID}. The state machine is enforced by the mutators ({@link #post},
 * {@link #recordPayment}, {@link #voidBill}); an illegal transition throws {@link
 * BillStateException} (→ HTTP 409). The GL side effects (Dr expense (+ VAT input) / Cr AP on post;
 * Dr AP / Cr cash-clearing on payment; contra on void) are posted by the writers in the same
 * transaction as these state changes.
 *
 * <p><strong>Expense recognition (accrual) &amp; scope.</strong> Posting recognises the expense in
 * the GL on the bill date, so the bill flows into the GL-derived income statement + balance sheet
 * ({@code 2000 AP}, LIABILITY) automatically. It does NOT feed the dimensional POS {@code /pnl}
 * dashboard read models (those are fed only by {@code SaleRecorded}/{@code ExpenseRecorded}); the
 * authoritative accounting statements are the GL-derived ones. This is a deliberate Phase-2 scope
 * choice (see ADR 0015, the AP mirror of ADR 0014).
 *
 * <p><strong>Money.</strong> {@code subtotal} (Σ line totals), {@code tax} (illustrative input
 * VAT), {@code total} ({@code subtotal + tax}), and {@code paid} (Σ payments) are stored as BIGINT
 * minor units plus one {@code currency} column (never a float — rule 8; the "two-longs" shape, as a
 * {@code MoneyEmbeddable} per amount would clash on {@code currency}). {@link #outstanding()} is
 * {@code total − paid}.
 *
 * <p>Extends {@link Auditable}; under the {@code bill} RLS policy (V27).
 */
@Entity
@Table(name = "bill")
public class Bill extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "vendor_id", nullable = false, updatable = false)
  private UUID vendorId;

  /** Human-facing sequential number, assigned on {@link #post} (null while DRAFT). */
  @Column(name = "bill_number", length = 64)
  private String billNumber;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private BillStatus status;

  @Column(name = "bill_date")
  private LocalDate billDate;

  @Column(name = "due_date")
  private LocalDate dueDate;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Column(name = "subtotal_minor", nullable = false, updatable = false)
  private long subtotalMinor;

  @Column(name = "tax_minor", nullable = false, updatable = false)
  private long taxMinor;

  @Column(name = "total_minor", nullable = false, updatable = false)
  private long totalMinor;

  @Column(name = "paid_minor", nullable = false)
  private long paidMinor;

  @Column(name = "uses_illustrative_rules", nullable = false, updatable = false)
  private boolean usesIllustrativeRules;

  /**
   * The GL journal entry raised on {@link #post} (Dr expense / Cr AP (+ VAT input)); null while
   * DRAFT.
   */
  @Column(name = "journal_entry_id")
  private UUID journalEntryId;

  protected Bill() {
    // for JPA
  }

  /**
   * Creates a DRAFT bill. {@code total = subtotal + tax} must be strictly positive; subtotal and
   * tax must share a currency. No number, dates, or GL entry yet — those are assigned on {@link
   * #post}.
   *
   * @param vendorId the billing vendor (validated to exist in the tenant by the writer)
   * @param subtotal Σ of the line totals (positive)
   * @param tax the illustrative input VAT (zero for a non-taxable bill); same currency
   * @param usesIllustrativeRules whether {@code tax} was computed from the illustrative rate
   */
  public static Bill draft(
      UUID vendorId, Money subtotal, Money tax, boolean usesIllustrativeRules) {
    Objects.requireNonNull(vendorId, "vendorId");
    Objects.requireNonNull(subtotal, "subtotal");
    Objects.requireNonNull(tax, "tax");
    Money total = subtotal.plus(tax); // throws MismatchedCurrencyException if currencies differ
    if (!total.isPositive()) {
      throw new IllegalArgumentException("bill total must be strictly positive: " + total);
    }
    Bill bill = new Bill();
    bill.id = UUID.randomUUID();
    bill.vendorId = vendorId;
    bill.status = BillStatus.DRAFT;
    bill.currency = subtotal.currency().getCurrencyCode();
    bill.subtotalMinor = subtotal.amountMinor();
    bill.taxMinor = tax.amountMinor();
    bill.totalMinor = total.amountMinor();
    bill.paidMinor = 0L;
    bill.usesIllustrativeRules = usesIllustrativeRules;
    return bill;
  }

  /**
   * Transitions DRAFT → POSTED: assigns the number, bill/due dates, and links the GL entry.
   *
   * @throws BillStateException if the bill is not DRAFT
   */
  public void post(String billNumber, LocalDate billDate, LocalDate dueDate, UUID journalEntryId) {
    if (status != BillStatus.DRAFT) {
      throw new BillStateException("only a DRAFT bill can be posted; current status=" + status);
    }
    this.billNumber = Objects.requireNonNull(billNumber, "billNumber");
    this.billDate = Objects.requireNonNull(billDate, "billDate");
    this.dueDate = Objects.requireNonNull(dueDate, "dueDate");
    this.journalEntryId = Objects.requireNonNull(journalEntryId, "journalEntryId");
    this.status = BillStatus.POSTED;
  }

  /**
   * Records a payment against a POSTED / PARTIALLY_PAID bill, advancing {@code paid} and the
   * status. The amount must be in the bill currency and must not exceed the {@link #outstanding()}
   * balance.
   *
   * @throws BillStateException if the bill is not payable, or the amount overpays the balance
   */
  public void recordPayment(Money amount) {
    Objects.requireNonNull(amount, "amount");
    if (status != BillStatus.POSTED && status != BillStatus.PARTIALLY_PAID) {
      throw new BillStateException(
          "only a POSTED or PARTIALLY_PAID bill can be paid; current status=" + status);
    }
    Money newPaid = paid().plus(amount); // same-currency enforced by Money
    Money total = total();
    if (newPaid.compareTo(total) > 0) {
      throw new BillStateException(
          "payment " + amount + " exceeds the outstanding balance " + outstanding());
    }
    this.paidMinor = newPaid.amountMinor();
    this.status = newPaid.compareTo(total) == 0 ? BillStatus.PAID : BillStatus.PARTIALLY_PAID;
  }

  /** Whether this bill may be voided (a DRAFT, or a POSTED bill with no payments). */
  public boolean isVoidable() {
    return (status == BillStatus.DRAFT || status == BillStatus.POSTED) && paidMinor == 0L;
  }

  /**
   * Transitions to VOID. A DRAFT voids with no GL effect; a POSTED (unpaid) bill is voided by a
   * balanced contra GL entry posted by the writer. A partially/fully paid bill cannot be voided.
   *
   * @throws BillStateException if the bill is not voidable
   */
  public void voidBill() {
    if (!isVoidable()) {
      throw new BillStateException(
          "bill cannot be voided; status=" + status + " paidMinor=" + paidMinor);
    }
    this.status = BillStatus.VOID;
  }

  public UUID getId() {
    return id;
  }

  public UUID getVendorId() {
    return vendorId;
  }

  public String getBillNumber() {
    return billNumber;
  }

  public BillStatus getStatus() {
    return status;
  }

  public LocalDate getBillDate() {
    return billDate;
  }

  public LocalDate getDueDate() {
    return dueDate;
  }

  public String getCurrency() {
    return currency.strip();
  }

  /** Σ of the line totals (before tax), as {@link Money}. */
  public Money subtotal() {
    return Money.ofMinor(subtotalMinor, currency.strip());
  }

  /** The illustrative input VAT, as {@link Money} (zero for a non-taxable bill). */
  public Money tax() {
    return Money.ofMinor(taxMinor, currency.strip());
  }

  /** The grand total ({@code subtotal + tax}), as {@link Money}. */
  public Money total() {
    return Money.ofMinor(totalMinor, currency.strip());
  }

  /** The amount paid so far, as {@link Money}. */
  public Money paid() {
    return Money.ofMinor(paidMinor, currency.strip());
  }

  /** The outstanding balance ({@code total − paid}), as {@link Money}. */
  public Money outstanding() {
    return total().minus(paid());
  }

  public boolean isUsesIllustrativeRules() {
    return usesIllustrativeRules;
  }

  public UUID getJournalEntryId() {
    return journalEntryId;
  }
}
