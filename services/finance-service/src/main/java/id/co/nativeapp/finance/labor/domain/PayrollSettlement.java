package id.co.nativeapp.finance.labor.domain;

import id.co.nativeapp.money.Money;
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
 * A {@code payroll_settlement} row — the one-shot record that a payroll liability bucket ({@link
 * SettlementKind}) of a specific {@code payroll_run_ledger} row has been PAID (ADR 0032, Track P
 * phase P5): {@code Dr <bucket role account> / Cr CASH_CLEARING} posted as {@link #journalEntryId}.
 * At most one row exists per {@code (company_id, payroll_run_ledger_id, kind)} (V41 {@code
 * uq_payroll_settlement_once}) — settling the same bucket twice is rejected before a second row is
 * ever built (see {@code PayrollSettlementWriter}).
 *
 * <p>Append-only: a settlement is never mutated or reversed by this phase (no un-settle flow exists
 * yet). Extends {@link Auditable} (rule 4) and is under FORCE RLS (rule 5, V41).
 */
@Entity
@Table(name = "payroll_settlement")
public class PayrollSettlement extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "payroll_run_ledger_id", nullable = false, updatable = false)
  private UUID payrollRunLedgerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, updatable = false, length = 16)
  private SettlementKind kind;

  @Column(name = "amount_minor", nullable = false, updatable = false)
  private long amountMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "amount_currency", nullable = false, updatable = false, length = 3)
  private String amountCurrency;

  @Column(name = "journal_entry_id", nullable = false, updatable = false)
  private UUID journalEntryId;

  @Column(name = "settled_at", nullable = false, updatable = false)
  private Instant settledAt;

  /** The Idempotency-Key that produced this settlement — the replay lookup key. */
  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
  private String idempotencyKey;

  protected PayrollSettlement() {
    // for JPA
  }

  /** Creates a fresh settlement row with a newly generated id. The amount must be positive. */
  public PayrollSettlement(
      UUID payrollRunLedgerId,
      SettlementKind kind,
      Money amount,
      UUID journalEntryId,
      Instant settledAt,
      String idempotencyKey) {
    Objects.requireNonNull(amount, "amount");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("settlement amount must be strictly positive: " + amount);
    }
    this.id = UUID.randomUUID();
    this.payrollRunLedgerId = Objects.requireNonNull(payrollRunLedgerId, "payrollRunLedgerId");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.amountMinor = amount.amountMinor();
    this.amountCurrency = amount.currency().getCurrencyCode();
    this.journalEntryId = Objects.requireNonNull(journalEntryId, "journalEntryId");
    this.settledAt = Objects.requireNonNull(settledAt, "settledAt");
    this.idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPayrollRunLedgerId() {
    return payrollRunLedgerId;
  }

  public SettlementKind getKind() {
    return kind;
  }

  /** The settled amount as {@link Money}. */
  public Money amount() {
    return Money.ofMinor(amountMinor, amountCurrency.strip());
  }

  public UUID getJournalEntryId() {
    return journalEntryId;
  }

  public Instant getSettledAt() {
    return settledAt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }
}
