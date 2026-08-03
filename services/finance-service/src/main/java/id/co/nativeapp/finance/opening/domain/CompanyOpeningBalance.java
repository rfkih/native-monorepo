package id.co.nativeapp.finance.opening.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The once-only {@code company_opening_balance} marker (ADR 0037, V46) — records that a company has
 * posted its opening balance sheet, and the {@code payloadFingerprint} (a SHA-256 of as-of +
 * currency + the canonicalized lines) the Idempotency-Key replay compares. {@code
 * UNIQUE(company_id)} makes it genuinely once-only; there is no amend/revise flow.
 *
 * <p>{@code openingEntryId} is the posted balanced {@code JournalEntry} (Dr assets / Cr liabilities
 * + equity + the OBE plug) — a plain UUID, not an FK (the settlement-table family convention: the
 * entry is built and persisted in the same transaction). {@code totalMinor} is the entry's Σdebit;
 * {@code plugMinor} is the signed residual credited to Opening Balance Equity (negative = it was a
 * debit; zero = the user's sheet already balanced). Money rule 8. Extends {@link Auditable} (rule
 * 4) and is under FORCE RLS (rule 5, V46).
 */
@Entity
@Table(name = "company_opening_balance")
public class CompanyOpeningBalance extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "opening_entry_id", nullable = false, updatable = false)
  private UUID openingEntryId;

  @Column(name = "as_of_date", nullable = false, updatable = false)
  private LocalDate asOfDate;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @Column(name = "total_minor", nullable = false, updatable = false)
  private long totalMinor;

  @Column(name = "plug_minor", nullable = false, updatable = false)
  private long plugMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  /**
   * SHA-256 (hex) of as-of + currency + the canonicalized lines — the replay payload fingerprint.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "payload_fingerprint", nullable = false, updatable = false, length = 64)
  private String payloadFingerprint;

  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
  private String idempotencyKey;

  protected CompanyOpeningBalance() {
    // for JPA
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  public CompanyOpeningBalance(
      UUID openingEntryId,
      LocalDate asOfDate,
      String period,
      long totalMinor,
      long plugMinor,
      String currency,
      String payloadFingerprint,
      String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.openingEntryId = Objects.requireNonNull(openingEntryId, "openingEntryId");
    this.asOfDate = Objects.requireNonNull(asOfDate, "asOfDate");
    this.period = Objects.requireNonNull(period, "period");
    this.totalMinor = totalMinor;
    this.plugMinor = plugMinor;
    this.currency = Objects.requireNonNull(currency, "currency");
    this.payloadFingerprint = Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }

  public UUID getId() {
    return id;
  }

  public UUID getOpeningEntryId() {
    return openingEntryId;
  }

  public LocalDate getAsOfDate() {
    return asOfDate;
  }

  public String getPeriod() {
    return period;
  }

  public long getTotalMinor() {
    return totalMinor;
  }

  public long getPlugMinor() {
    return plugMinor;
  }

  public String getCurrency() {
    return currency;
  }

  /** The replay payload fingerprint (stripped of any {@code CHAR} padding). */
  public String getPayloadFingerprint() {
    return payloadFingerprint == null ? null : payloadFingerprint.strip();
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }
}
