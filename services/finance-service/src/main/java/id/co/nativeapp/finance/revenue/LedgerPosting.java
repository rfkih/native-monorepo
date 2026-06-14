package id.co.nativeapp.finance.revenue;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code ledger_posting} aggregate — finance-service's append-only dimensional ledger row. One
 * posting is created per consumed {@code SaleRecorded}: a {@link PostingType#REVENUE} posting in
 * the sale's transaction currency.
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns ({@code
 * created_at}/{@code created_by}, {@code updated_at}/{@code updated_by}, {@code version}, {@code
 * company_id}) and is covered by the {@code ledger_posting} RLS policy in the Flyway baseline (rule
 * 4 + rule 5).
 *
 * <p>The monetary amount is a {@code libs/money} {@link Money} (rule 8 — integer minor units +
 * ISO-4217 currency, never a float), persisted via {@link MoneyEmbeddable} as {@code amount_minor
 * BIGINT} + {@code currency CHAR(3)}.
 *
 * <p><strong>Append-only + idempotent.</strong> A posting is never mutated after creation, and
 * {@code source_event_id} (the consumed event's UUID) carries a {@code UNIQUE} constraint so a
 * re-delivered {@code SaleRecorded} can never produce a second posting — the database backstop
 * behind the {@code ProcessedEventStore} dedupe (rule 3).
 */
@Entity
@Table(name = "ledger_posting")
public class LedgerPosting extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @Enumerated(EnumType.STRING)
  @Column(name = "posting_type", nullable = false, updatable = false, length = 32)
  private PostingType postingType;

  @Embedded private MoneyEmbeddable amount;

  @Column(name = "source_event_id", nullable = false, updatable = false)
  private UUID sourceEventId;

  protected LedgerPosting() {
    // for JPA
  }

  /**
   * Creates a revenue posting from a consumed sale.
   *
   * @param businessId the originating business unit
   * @param period the accounting period {@code YYYY-MM} (use {@link #periodOf(Instant)})
   * @param amount the posting amount as {@link Money} (never a float)
   * @param sourceEventId the consumed event's UUID — the idempotency key (UNIQUE in the schema)
   */
  public LedgerPosting(UUID businessId, String period, Money amount, UUID sourceEventId) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.period = Objects.requireNonNull(period, "period");
    this.postingType = PostingType.REVENUE;
    this.amount = MoneyEmbeddable.of(amount);
    this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
  }

  /**
   * Derives the accounting period ({@code YYYY-MM}) from an instant, in UTC. The producer stamps
   * {@code occurred_at} as epoch millis UTC, so the period boundary is unambiguous and stable
   * regardless of the consumer's local zone.
   */
  public static String periodOf(Instant occurredAt) {
    Objects.requireNonNull(occurredAt, "occurredAt");
    return YearMonth.from(occurredAt.atZone(ZoneOffset.UTC)).toString();
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getPeriod() {
    return period;
  }

  public PostingType getPostingType() {
    return postingType;
  }

  /** The posting amount as a {@link Money} value (reconstructed from its columns). */
  public Money getAmount() {
    return amount.toMoney();
  }

  public UUID getSourceEventId() {
    return sourceEventId;
  }
}
