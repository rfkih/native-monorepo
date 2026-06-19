package id.co.nativeapp.finance.gl.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code journal_entry} aggregate — the double-entry header row. Each event that finance
 * consumes produces exactly one balanced {@code JournalEntry} (via {@link #balanced}), alongside
 * the existing dimensional ledger posting, in the same transaction.
 *
 * <p><strong>Balanced invariant.</strong> An entry cannot exist in memory unless {@link #balanced}
 * produced it. The factory enforces: ≥2 lines, each single-sided, all lines in the same currency as
 * the entry, and {@code Σdebit == Σcredit > 0}; else {@link UnbalancedJournalException}.
 *
 * <p><strong>Idempotency.</strong> {@code source_event_id} is {@code UNIQUE} in the schema (V13) —
 * the database backstop behind the {@code ProcessedEventStore} dedupe. A re-delivered event can
 * never produce a second entry.
 *
 * <p><strong>Lines.</strong> The associated {@link JournalLine}s are stored in a {@link
 * #getLines()} transient list after construction. The writer saves the entry first, then saves each
 * line explicitly; the lines carry the correct {@link JournalLine#getEntryId()} FK pointing at this
 * entry's {@link #getId()}. This avoids Hibernate bidirectional-cascade complexity while keeping
 * the invariant-enforcement in the domain (the factory validates before any persistence).
 *
 * <p>Extends {@link Auditable}: every Native table carries the six audit + tenancy columns (rule
 * 4), and this table is under {@code FORCE ROW LEVEL SECURITY} scoped to {@code app.current_tenant}
 * (rule 5, V13).
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** Accounting period {@code YYYY-MM} — derived from {@code occurred_at} (UTC). */
  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  /** The instant the source event occurred (the producer's {@code occurred_at}). */
  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  /** Human-readable description for audit / the journal view. */
  @Column(name = "description", nullable = false, updatable = false, length = 500)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, updatable = false, length = 16)
  private JournalStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "posting_role", nullable = false, updatable = false, length = 16)
  private JournalPostingRole postingRole;

  /**
   * ISO-4217 currency code for the entire entry. All lines must share this currency; the balanced
   * factory enforces it.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  /**
   * The source event UUID — idempotency key (UNIQUE in the schema). A re-delivered event produces
   * the same id; the UNIQUE constraint and {@code ProcessedEventStore} together ensure exactly one
   * entry per event.
   */
  @Column(name = "source_event_id", nullable = false, updatable = false)
  private UUID sourceEventId;

  /**
   * Whether the lines of this entry were produced from an ILLUSTRATIVE-PLACEHOLDER posting rule
   * (V13 seed, {@code uses_illustrative=true}). Propagates the flag forward from the resolved
   * template. An SME replaces the seed with real rules at a higher version ({@code
   * uses_illustrative=false}); entries produced after that upgrade carry {@code false}.
   */
  @Column(name = "uses_illustrative_rules", nullable = false, updatable = false)
  private boolean usesIllustrativeRules;

  /**
   * The lines validated by {@link #balanced} — transient (not persisted here; the writer saves each
   * line explicitly via the line repository after saving the entry header).
   */
  @Transient private List<JournalLine> lines = new ArrayList<>();

  protected JournalEntry() {
    // for JPA
  }

  /**
   * Factory — the ONLY way to create a valid {@code JournalEntry}. Enforces the full double-entry
   * invariant:
   *
   * <ol>
   *   <li>≥2 lines (a journal must have at least a debit and a credit line).
   *   <li>Each line is single-sided ({@code debit_minor=0 XOR credit_minor=0}).
   *   <li>All lines are in {@code currency} (same currency as the header).
   *   <li>{@code Σdebit == Σcredit} and the sum is {@code > 0}.
   * </ol>
   *
   * @param entryId the UUID to assign as this entry's primary key — the caller pre-allocates this
   *     UUID and uses it when constructing the {@link JournalLine}s so that the FK is consistent
   *     before any persistence happens
   * @param period accounting period {@code YYYY-MM} (use {@link
   *     id.co.nativeapp.finance.revenue.domain.LedgerPosting#periodOf})
   * @param occurredAt the source event's occurred-at instant
   * @param description a human-readable description for audit
   * @param currency ISO-4217 currency code — all lines must share this currency
   * @param sourceEventId the consumed event's UUID (idempotency key)
   * @param usesIllustrativeRules whether the resolved posting template was illustrative
   * @param lines ≥2 fully constructed {@link JournalLine}s (created by {@link JournalLine#debit} /
   *     {@link JournalLine#credit}); each line's {@code entryId} must equal {@code entryId}
   * @throws UnbalancedJournalException if any invariant is violated
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static JournalEntry balanced(
      UUID entryId,
      String period,
      Instant occurredAt,
      String description,
      String currency,
      UUID sourceEventId,
      boolean usesIllustrativeRules,
      List<JournalLine> lines) {

    Objects.requireNonNull(entryId, "entryId");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(sourceEventId, "sourceEventId");
    Objects.requireNonNull(lines, "lines");

    if (lines.size() < 2) {
      throw new UnbalancedJournalException(
          "A journal entry must have at least 2 lines; got " + lines.size());
    }

    long totalDebit = 0L;
    long totalCredit = 0L;

    for (JournalLine line : lines) {
      // Single-sided: XOR(debit>0, credit>0) — the SQL CHECK mirrors this.
      boolean isDebit = line.getDebitMinor() > 0;
      boolean isCredit = line.getCreditMinor() > 0;
      if (isDebit == isCredit) {
        throw new UnbalancedJournalException(
            "Line "
                + line.getLineNo()
                + " is not single-sided: debit="
                + line.getDebitMinor()
                + " credit="
                + line.getCreditMinor());
      }
      // Currency consistency.
      String lineCurrency = line.getCurrency().strip();
      if (!currency.equals(lineCurrency)) {
        throw new UnbalancedJournalException(
            "Line "
                + line.getLineNo()
                + " currency "
                + lineCurrency
                + " does not match entry currency "
                + currency);
      }
      totalDebit = Math.addExact(totalDebit, line.getDebitMinor());
      totalCredit = Math.addExact(totalCredit, line.getCreditMinor());
    }

    if (totalDebit != totalCredit) {
      throw new UnbalancedJournalException(
          "Journal is unbalanced: Σdebit=" + totalDebit + " Σcredit=" + totalCredit);
    }
    if (totalDebit <= 0) {
      throw new UnbalancedJournalException(
          "Journal total must be strictly positive; got Σdebit=" + totalDebit);
    }

    JournalEntry entry = new JournalEntry();
    entry.id = entryId;
    entry.period = period;
    entry.occurredAt = occurredAt;
    entry.description = description;
    entry.status = JournalStatus.POSTED;
    entry.postingRole = JournalPostingRole.PRIMARY;
    entry.currency = currency;
    entry.sourceEventId = sourceEventId;
    entry.usesIllustrativeRules = usesIllustrativeRules;
    entry.lines = new ArrayList<>(lines);
    return entry;
  }

  public UUID getId() {
    return id;
  }

  public String getPeriod() {
    return period;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getDescription() {
    return description;
  }

  public JournalStatus getStatus() {
    return status;
  }

  public JournalPostingRole getPostingRole() {
    return postingRole;
  }

  public String getCurrency() {
    return currency;
  }

  public UUID getSourceEventId() {
    return sourceEventId;
  }

  public boolean isUsesIllustrativeRules() {
    return usesIllustrativeRules;
  }

  /** The validated lines (transient — the writer saves them separately after the entry header). */
  public List<JournalLine> getLines() {
    return List.copyOf(lines);
  }

  /**
   * The total debit (== total credit for a balanced entry) as a {@link Money}. Convenience for
   * testing and the trial-balance reader.
   */
  public Money totalDebit() {
    long sum = lines.stream().mapToLong(JournalLine::getDebitMinor).sum();
    return Money.ofMinor(sum, currency.strip());
  }
}
