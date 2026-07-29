package id.co.nativeapp.finance.assets.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One item's posted share within an {@link AmortizationRun} (Phase 6) — the sub-ledger detail: the
 * item (asset or deferral), the schedule month index, the exact-sum amount, and the 2-leg journal
 * entry it raised ({@code journal_entry_id} is NULL when the month's share rounds to zero — a
 * zero-amount entry cannot exist). The line's id is the entry's {@code source_event_id} (UNIQUE —
 * the DB idempotency backstop behind the run seal).
 *
 * <p>Book value / remaining balance = the item's opening amount − Σ its line amounts (an RLS-scoped
 * SUM over this table — no per-item GL query). Extends {@link Auditable}; V34 RLS.
 */
@Entity
@Table(name = "amortization_run_line")
public class AmortizationRunLine extends Auditable {

  /** What kind of item the line amortizes. */
  public enum ItemType {
    ASSET,
    DEFERRAL
  }

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "run_id", nullable = false, updatable = false)
  private UUID runId;

  @Enumerated(EnumType.STRING)
  @Column(name = "item_type", nullable = false, updatable = false, length = 10)
  private ItemType itemType;

  @Column(name = "item_id", nullable = false, updatable = false)
  private UUID itemId;

  @Column(name = "month_index", nullable = false, updatable = false)
  private int monthIndex;

  @Column(name = "amount_minor", nullable = false, updatable = false)
  private long amountMinor;

  @Column(name = "journal_entry_id", updatable = false)
  private UUID journalEntryId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  protected AmortizationRunLine() {
    // for JPA
  }

  /**
   * Creates a run line. {@code journalEntryId} is null iff {@code amountMinor} is zero (a zero
   * month records the schedule step but raises no entry).
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static AmortizationRunLine of(
      UUID id,
      UUID runId,
      ItemType itemType,
      UUID itemId,
      int monthIndex,
      long amountMinor,
      UUID journalEntryId,
      String currencyCode) {
    if (amountMinor < 0L) {
      throw new IllegalArgumentException("run line amount must be non-negative: " + amountMinor);
    }
    if ((amountMinor == 0L) != (journalEntryId == null)) {
      throw new IllegalArgumentException(
          "a zero-amount line must have no journal entry, a positive line must have one");
    }
    AmortizationRunLine line = new AmortizationRunLine();
    line.id = Objects.requireNonNull(id, "id");
    line.runId = Objects.requireNonNull(runId, "runId");
    line.itemType = Objects.requireNonNull(itemType, "itemType");
    line.itemId = Objects.requireNonNull(itemId, "itemId");
    line.monthIndex = monthIndex;
    line.amountMinor = amountMinor;
    line.journalEntryId = journalEntryId;
    line.currency = Objects.requireNonNull(currencyCode, "currencyCode").strip();
    return line;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRunId() {
    return runId;
  }

  public ItemType getItemType() {
    return itemType;
  }

  public UUID getItemId() {
    return itemId;
  }

  public int getMonthIndex() {
    return monthIndex;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public UUID getJournalEntryId() {
    return journalEntryId;
  }

  public String getCurrency() {
    return currency.strip();
  }
}
