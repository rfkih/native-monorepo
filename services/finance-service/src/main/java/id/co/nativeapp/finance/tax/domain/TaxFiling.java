package id.co.nativeapp.finance.tax.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code tax_filing} aggregate (Phase 4 Tax / PPN, ADR 0017) — the record that a company FILED
 * a period's PPN (VAT) return. The tax analogue of {@link
 * id.co.nativeapp.finance.withinclose.domain.WithinCompanyClose}: a {@code UNIQUE (company_id,
 * period, tax_type)} guards it (idempotency — a re-file finds the row and no-ops).
 *
 * <p>The VAT amounts are DERIVED from the double-entry GL (output = credit-net of VAT_OUTPUT /
 * 2200, input = debit-net of VAT_INPUT / 1300, over the period), snapshotted here at file time;
 * this table is NOT the source of truth for the ledger. {@code netMinor} is the magnitude {@code
 * |output − input|} and {@code netDirection} carries its sign (PAYABLE when output ≥ input,
 * CREDITABLE otherwise) — so the settlement rule ("PAYABLE only") needs no re-derivation.
 *
 * <p>{@code status}, {@code settlementEntryId} and {@code settledAt} are the only mutable columns
 * (updated by {@link #settle}); everything else is set once at file time. Extends {@link
 * Auditable}, so it carries the six audit + tenancy columns and is covered by the {@code
 * tax_filing} RLS policy (V31), scoped to {@code app.current_tenant} (rules 4 + 5).
 */
@Entity
@Table(name = "tax_filing")
public class TaxFiling extends Auditable {

  /** The tax type this slice handles — Indonesian VAT. The column leaves room for PPh etc. */
  public static final String TAX_TYPE_PPN = "PPN";

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @Column(name = "tax_type", nullable = false, updatable = false, length = 16)
  private String taxType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private TaxFilingStatus status;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Column(name = "output_vat_minor", nullable = false, updatable = false)
  private long outputVatMinor;

  @Column(name = "input_vat_minor", nullable = false, updatable = false)
  private long inputVatMinor;

  /**
   * The magnitude {@code |output − input|} in minor units; {@link #netDirection} carries the sign.
   */
  @Column(name = "net_minor", nullable = false, updatable = false)
  private long netMinor;

  @Enumerated(EnumType.STRING)
  @Column(name = "net_direction", nullable = false, updatable = false, length = 12)
  private NetDirection netDirection;

  /** The netting {@code journal_entry} raised on file (Dr 2200 / Cr 1300 / net → 2300 or 1310). */
  @Column(name = "filing_entry_id", nullable = false, updatable = false)
  private UUID filingEntryId;

  /**
   * The settlement {@code journal_entry} raised on settle (Dr 2300 / Cr 1900); NULL until settled.
   */
  @Column(name = "settlement_entry_id")
  private UUID settlementEntryId;

  @Column(name = "filed_at", nullable = false, updatable = false)
  private Instant filedAt;

  @Column(name = "settled_at")
  private Instant settledAt;

  protected TaxFiling() {
    // for JPA
  }

  /**
   * Creates a FILED PPN return for {@code (the bound company, period)}. {@code netMinor} is
   * computed as {@code |output − input|} and {@code netDirection} from the sign (PAYABLE when
   * {@code output ≥ input}). The caller has already posted the netting {@code JournalEntry} keyed
   * on {@code filingEntryId}.
   *
   * @param id the aggregate id (pre-allocated by the writer, also the netting entry's {@code
   *     source_event_id})
   * @param period the accounting period {@code YYYY-MM}
   * @param currencyCode the period's GL currency (ISO-4217)
   * @param outputVatMinor the period's output VAT (credit-net of VAT_OUTPUT), in minor units
   * @param inputVatMinor the period's input VAT (debit-net of VAT_INPUT), in minor units
   * @param filingEntryId the netting journal entry's id
   * @param filedAt the file instant
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static TaxFiling file(
      UUID id,
      String period,
      String currencyCode,
      long outputVatMinor,
      long inputVatMinor,
      UUID filingEntryId,
      Instant filedAt) {
    long signedNet = Math.subtractExact(outputVatMinor, inputVatMinor);
    TaxFiling filing = new TaxFiling();
    filing.id = Objects.requireNonNull(id, "id");
    filing.period = Objects.requireNonNull(period, "period");
    filing.taxType = TAX_TYPE_PPN;
    filing.status = TaxFilingStatus.FILED;
    filing.currency = Objects.requireNonNull(currencyCode, "currencyCode").strip();
    filing.outputVatMinor = outputVatMinor;
    filing.inputVatMinor = inputVatMinor;
    filing.netMinor = Math.abs(signedNet);
    filing.netDirection = signedNet >= 0 ? NetDirection.PAYABLE : NetDirection.CREDITABLE;
    filing.filingEntryId = Objects.requireNonNull(filingEntryId, "filingEntryId");
    filing.filedAt = Objects.requireNonNull(filedAt, "filedAt");
    return filing;
  }

  /**
   * Settles a net-PAYABLE return: FILED → SETTLED, recording the settlement journal entry. The
   * caller has already posted the {@code Dr VAT_PAYABLE / Cr CASH_CLEARING} entry keyed on {@code
   * settlementEntryId}.
   *
   * @throws TaxFilingStateException if the return is already SETTLED, or is CREDITABLE / zero-net
   *     (nothing to settle)
   */
  public void settle(UUID settlementEntryId, Instant settledAt) {
    if (status != TaxFilingStatus.FILED) {
      throw new TaxFilingStateException(
          "tax filing is not in FILED state: " + id + " (" + status + ")");
    }
    if (netDirection != NetDirection.PAYABLE || netMinor <= 0L) {
      throw new TaxFilingStateException(
          "only a net-payable tax filing with a positive balance can be settled: " + id);
    }
    this.status = TaxFilingStatus.SETTLED;
    this.settlementEntryId = Objects.requireNonNull(settlementEntryId, "settlementEntryId");
    this.settledAt = Objects.requireNonNull(settledAt, "settledAt");
  }

  /** {@code true} when this return can be settled (a FILED, positive net-PAYABLE return). */
  public boolean isSettleable() {
    return status == TaxFilingStatus.FILED && netDirection == NetDirection.PAYABLE && netMinor > 0L;
  }

  public UUID getId() {
    return id;
  }

  public String getPeriod() {
    return period;
  }

  public String getTaxType() {
    return taxType;
  }

  public TaxFilingStatus getStatus() {
    return status;
  }

  /** The period's GL currency (ISO-4217, stripped of any {@code CHAR} padding). */
  public String getCurrency() {
    return currency.strip();
  }

  public long getOutputVatMinor() {
    return outputVatMinor;
  }

  public long getInputVatMinor() {
    return inputVatMinor;
  }

  public long getNetMinor() {
    return netMinor;
  }

  public NetDirection getNetDirection() {
    return netDirection;
  }

  public UUID getFilingEntryId() {
    return filingEntryId;
  }

  public UUID getSettlementEntryId() {
    return settlementEntryId;
  }

  public Instant getFiledAt() {
    return filedAt;
  }

  public Instant getSettledAt() {
    return settledAt;
  }
}
