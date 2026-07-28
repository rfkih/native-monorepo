package id.co.nativeapp.finance.ar.domain;

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
 * The {@code invoice} aggregate — an AR document billed to a {@link Customer} (Phase 1 AR).
 *
 * <p><strong>Lifecycle (enforced here).</strong> {@code DRAFT → ISSUED → PARTIALLY_PAID → PAID}, or
 * {@code DRAFT | ISSUED → VOID}. The state machine is enforced by the mutators ({@link #issue},
 * {@link #recordPayment}, {@link #voidInvoice}); an illegal transition throws {@link
 * InvoiceStateException} (→ HTTP 409). The GL side effects (Dr AR / Cr revenue on issue; Dr cash /
 * Cr AR on payment; contra on void) are posted by the writers in the same transaction as these
 * state changes.
 *
 * <p><strong>Money.</strong> {@code subtotal} (Σ line totals), {@code tax} (illustrative output
 * VAT), {@code total} ({@code subtotal + tax}), and {@code paid} (Σ payments) are stored as BIGINT
 * minor units plus one {@code currency} column (never a float — rule 8; the "two-longs" shape, as a
 * {@code MoneyEmbeddable} per amount would clash on {@code currency}). {@link #outstanding()} is
 * {@code total − paid}.
 *
 * <p>Extends {@link Auditable}; under the {@code invoice} RLS policy (V24).
 */
@Entity
@Table(name = "invoice")
public class Invoice extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "customer_id", nullable = false, updatable = false)
  private UUID customerId;

  /** Human-facing sequential number, assigned on {@link #issue} (null while DRAFT). */
  @Column(name = "invoice_number", length = 64)
  private String invoiceNumber;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private InvoiceStatus status;

  @Column(name = "issue_date")
  private LocalDate issueDate;

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
   * The GL journal entry raised on {@link #issue} (Dr AR / Cr revenue (+ VAT)); null while DRAFT.
   */
  @Column(name = "journal_entry_id")
  private UUID journalEntryId;

  protected Invoice() {
    // for JPA
  }

  /**
   * Creates a DRAFT invoice. {@code total = subtotal + tax} must be strictly positive; subtotal and
   * tax must share a currency. No number, dates, or GL entry yet — those are assigned on {@link
   * #issue}.
   *
   * @param customerId the billed customer (validated to exist in the tenant by the writer)
   * @param subtotal Σ of the line totals (positive)
   * @param tax the illustrative output VAT (zero for a non-taxable invoice); same currency
   * @param usesIllustrativeRules whether {@code tax} was computed from the illustrative rate
   */
  public static Invoice draft(
      UUID customerId, Money subtotal, Money tax, boolean usesIllustrativeRules) {
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(subtotal, "subtotal");
    Objects.requireNonNull(tax, "tax");
    Money total = subtotal.plus(tax); // throws MismatchedCurrencyException if currencies differ
    if (!total.isPositive()) {
      throw new IllegalArgumentException("invoice total must be strictly positive: " + total);
    }
    Invoice invoice = new Invoice();
    invoice.id = UUID.randomUUID();
    invoice.customerId = customerId;
    invoice.status = InvoiceStatus.DRAFT;
    invoice.currency = subtotal.currency().getCurrencyCode();
    invoice.subtotalMinor = subtotal.amountMinor();
    invoice.taxMinor = tax.amountMinor();
    invoice.totalMinor = total.amountMinor();
    invoice.paidMinor = 0L;
    invoice.usesIllustrativeRules = usesIllustrativeRules;
    return invoice;
  }

  /**
   * Transitions DRAFT → ISSUED: assigns the number, issue/due dates, and links the GL entry.
   *
   * @throws InvoiceStateException if the invoice is not DRAFT
   */
  public void issue(
      String invoiceNumber, LocalDate issueDate, LocalDate dueDate, UUID journalEntryId) {
    if (status != InvoiceStatus.DRAFT) {
      throw new InvoiceStateException(
          "only a DRAFT invoice can be issued; current status=" + status);
    }
    this.invoiceNumber = Objects.requireNonNull(invoiceNumber, "invoiceNumber");
    this.issueDate = Objects.requireNonNull(issueDate, "issueDate");
    this.dueDate = Objects.requireNonNull(dueDate, "dueDate");
    this.journalEntryId = Objects.requireNonNull(journalEntryId, "journalEntryId");
    this.status = InvoiceStatus.ISSUED;
  }

  /**
   * Records a payment against an ISSUED / PARTIALLY_PAID invoice, advancing {@code paid} and the
   * status. The amount must be in the invoice currency and must not exceed the {@link
   * #outstanding()} balance.
   *
   * @throws InvoiceStateException if the invoice is not payable, or the amount overpays the balance
   */
  public void recordPayment(Money amount) {
    Objects.requireNonNull(amount, "amount");
    if (status != InvoiceStatus.ISSUED && status != InvoiceStatus.PARTIALLY_PAID) {
      throw new InvoiceStateException(
          "only an ISSUED or PARTIALLY_PAID invoice can be paid; current status=" + status);
    }
    Money newPaid = paid().plus(amount); // same-currency enforced by Money
    Money total = total();
    if (newPaid.compareTo(total) > 0) {
      throw new InvoiceStateException(
          "payment " + amount + " exceeds the outstanding balance " + outstanding());
    }
    this.paidMinor = newPaid.amountMinor();
    this.status = newPaid.compareTo(total) == 0 ? InvoiceStatus.PAID : InvoiceStatus.PARTIALLY_PAID;
  }

  /** Whether this invoice may be voided (a DRAFT, or an ISSUED invoice with no payments). */
  public boolean isVoidable() {
    return (status == InvoiceStatus.DRAFT || status == InvoiceStatus.ISSUED) && paidMinor == 0L;
  }

  /**
   * Transitions to VOID. A DRAFT voids with no GL effect; an ISSUED (unpaid) invoice is voided by a
   * balanced contra GL entry posted by the writer. A partially/fully paid invoice cannot be voided.
   *
   * @throws InvoiceStateException if the invoice is not voidable
   */
  public void voidInvoice() {
    if (!isVoidable()) {
      throw new InvoiceStateException(
          "invoice cannot be voided; status=" + status + " paidMinor=" + paidMinor);
    }
    this.status = InvoiceStatus.VOID;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public String getInvoiceNumber() {
    return invoiceNumber;
  }

  public InvoiceStatus getStatus() {
    return status;
  }

  public LocalDate getIssueDate() {
    return issueDate;
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

  /** The illustrative output VAT, as {@link Money} (zero for a non-taxable invoice). */
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
