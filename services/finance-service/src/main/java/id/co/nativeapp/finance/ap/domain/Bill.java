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
import java.util.Set;
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

  /** The PPN rates a bill may carry, in basis points (ADR 0084): none, 11 % or 12 %. */
  public static final Set<Integer> ALLOWED_TAX_BP = Set.of(0, 1_100, 1_200);

  /** The rate a pre-ADR-0084 "taxable" bill was computed at — the official 11 % PPN (ADR 0042). */
  public static final int DEFAULT_TAX_BP = 1_100;

  /** The longest payment term a bill may carry — ten years; beyond that the due date is a typo. */
  public static final int MAX_TERM_DAYS = 3_650;

  /** No invoice predates the fleet's earliest posting period; a later one is a typo too. */
  public static final LocalDate EARLIEST_BILL_DATE = LocalDate.of(2000, 1, 1);

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

  /**
   * The VENDOR's own invoice number (ADR 0084) — the duplicate-detection key per (tenant, vendor)
   * among live bills ({@code uq_bill_company_vendor_invoice}, V69). Null for legacy drafts.
   */
  @Column(name = "vendor_invoice_number", length = 64, updatable = false)
  private String vendorInvoiceNumber;

  /**
   * The invoice date when given at draft time (ADR 0084); otherwise the posting day, set on post.
   */
  @Column(name = "bill_date")
  private LocalDate billDate;

  /** The payment term chosen at draft time (ADR 0084); {@link #post} uses it absent an override. */
  @Column(name = "term_days", updatable = false)
  private Integer termDays;

  /** Header discount in minor units (ADR 0084); reduces the net before tax. */
  @Column(name = "discount_minor", nullable = false, updatable = false)
  private long discountMinor;

  /** The PPN rate applied to the net, in basis points: 0, 1100 or 1200 (ADR 0084). */
  @Column(name = "tax_bp", nullable = false, updatable = false)
  private int taxBp;

  /** Internal note (ADR 0084) — never printed, never posted. */
  @Column(name = "note", length = 1000, updatable = false)
  private String note;

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
    Objects.requireNonNull(subtotal, "subtotal");
    return draft(
        vendorId,
        subtotal,
        Money.zero(subtotal.currency()),
        tax,
        tax.isZero() ? 0 : DEFAULT_TAX_BP,
        new InvoiceDetails(null, null, null, null),
        usesIllustrativeRules);
  }

  /**
   * The invoice as the vendor wrote it (ADR 0084) — every part optional, so a legacy client that
   * sends none of it still drafts a bill.
   *
   * @param vendorInvoiceNumber the vendor's own number (stripped; blank → null), ≤ 64 chars
   * @param billDate the invoice date; null → the posting day, assigned on post
   * @param termDays the payment term in days; null → the post-time default
   * @param note an internal note (stripped; blank → null), ≤ 1000 chars
   */
  public record InvoiceDetails(
      String vendorInvoiceNumber, LocalDate billDate, Integer termDays, String note) {}

  /**
   * Creates a DRAFT bill with the full ADR 0084 arithmetic: {@code net = subtotal − discount},
   * {@code total = net + tax}. The net (and so the total) must be strictly positive; subtotal,
   * discount and tax must share a currency; {@code taxBp} is the rate {@code tax} was computed at
   * and must be one of {@link #ALLOWED_TAX_BP}. No number, GL entry or due date yet — those are
   * assigned on {@link #post}.
   *
   * @param vendorId the billing vendor (validated to exist in the tenant by the writer)
   * @param subtotal Σ of the line totals (positive), before any discount
   * @param discount the header discount, {@code 0 ≤ discount ≤ subtotal}
   * @param tax the input VAT on the net (zero for a non-taxable bill); same currency
   * @param taxBp the rate {@code tax} was computed at, basis points (0 / 1100 / 1200)
   * @param details the invoice number, date, terms and note the vendor's paper carries
   * @param usesIllustrativeRules whether {@code tax} was computed from an illustrative rate
   */
  public static Bill draft(
      UUID vendorId,
      Money subtotal,
      Money discount,
      Money tax,
      int taxBp,
      InvoiceDetails details,
      boolean usesIllustrativeRules) {
    Objects.requireNonNull(vendorId, "vendorId");
    Objects.requireNonNull(subtotal, "subtotal");
    Objects.requireNonNull(discount, "discount");
    Objects.requireNonNull(tax, "tax");
    Objects.requireNonNull(details, "details");
    if (discount.isNegative() || discount.compareTo(subtotal) > 0) {
      throw new IllegalArgumentException(
          "bill discount must be between zero and the subtotal: " + discount + " of " + subtotal);
    }
    if (!ALLOWED_TAX_BP.contains(taxBp)) {
      throw new IllegalArgumentException("bill tax rate must be one of " + ALLOWED_TAX_BP + " bp");
    }
    if (details.termDays() != null
        && (details.termDays() < 0 || details.termDays() > MAX_TERM_DAYS)) {
      throw new IllegalArgumentException("bill term days must be within 0.." + MAX_TERM_DAYS);
    }
    if (details.billDate() != null
        && (details.billDate().isBefore(EARLIEST_BILL_DATE)
            || details.billDate().isAfter(LocalDate.now().plusYears(1)))) {
      throw new IllegalArgumentException(
          "bill date must be between 2000-01-01 and a year from now");
    }
    Money net = subtotal.minus(discount); // throws MismatchedCurrencyException if currencies differ
    Money total = net.plus(tax);
    if (!net.isPositive() || !total.isPositive()) {
      throw new IllegalArgumentException("bill total must be strictly positive: " + total);
    }
    Bill bill = new Bill();
    bill.id = UUID.randomUUID();
    bill.vendorId = vendorId;
    bill.status = BillStatus.DRAFT;
    bill.currency = subtotal.currency().getCurrencyCode();
    bill.subtotalMinor = subtotal.amountMinor();
    bill.discountMinor = discount.amountMinor();
    bill.taxMinor = tax.amountMinor();
    bill.taxBp = taxBp;
    bill.totalMinor = total.amountMinor();
    bill.paidMinor = 0L;
    bill.usesIllustrativeRules = usesIllustrativeRules;
    bill.vendorInvoiceNumber =
        blankToNull(details.vendorInvoiceNumber(), 64, "vendorInvoiceNumber");
    bill.billDate = details.billDate();
    bill.termDays = details.termDays();
    bill.note = blankToNull(details.note(), 1000, "note");
    return bill;
  }

  private static String blankToNull(String value, int max, String field) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > max) {
      throw new IllegalArgumentException(field + " must be at most " + max + " characters");
    }
    return trimmed;
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

  /** Σ of the line totals (before discount and tax), as {@link Money}. */
  public Money subtotal() {
    return Money.ofMinor(subtotalMinor, currency.strip());
  }

  /** The header discount (ADR 0084), as {@link Money}; zero when none. */
  public Money discount() {
    return Money.ofMinor(discountMinor, currency.strip());
  }

  /** The taxable net ({@code subtotal − discount}) — the DPP the tax was computed on. */
  public Money net() {
    return subtotal().minus(discount());
  }

  public String getVendorInvoiceNumber() {
    return vendorInvoiceNumber;
  }

  public Integer getTermDays() {
    return termDays;
  }

  public int getTaxBp() {
    return taxBp;
  }

  public String getNote() {
    return note;
  }

  /** The illustrative input VAT, as {@link Money} (zero for a non-taxable bill). */
  public Money tax() {
    return Money.ofMinor(taxMinor, currency.strip());
  }

  /** The grand total ({@code subtotal − discount + tax}), as {@link Money}. */
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
