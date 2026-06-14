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
 * The {@code ledger_posting} aggregate — finance-service's append-only DIMENSIONAL ledger row. One
 * posting is created per consumed event: a {@link PostingType#REVENUE} posting per {@code
 * SaleRecorded}, a {@link PostingType#EXPENSE} posting per {@code ExpenseRecorded} (#21), each in
 * the source event's transaction currency.
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns ({@code
 * created_at}/{@code created_by}, {@code updated_at}/{@code updated_by}, {@code version}, {@code
 * company_id}) and is covered by the {@code ledger_posting} RLS policy in the Flyway baseline (rule
 * 4 + rule 5).
 *
 * <p><strong>Dimensional.</strong> Every posting carries a {@code gl_account_code} — the {@code
 * chart_of_account} account it resolved to via the versioned, effective-dated {@code mapping_rule}
 * (#21). It is the dimension the consolidated P&amp;L aggregates by alongside {@code posting_type},
 * {@code period}, and {@code company_id}. The account is resolved <em>on write</em> (CQRS) and
 * stamped here; aggregation happens on read.
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

  /**
   * The {@code chart_of_account} account this posting was mapped to (the dimensional account code),
   * resolved on write via the effective-dated {@code mapping_rule}. The aggregation dimension the
   * P&amp;L groups by alongside {@code posting_type}.
   */
  @Column(name = "gl_account_code", nullable = false, updatable = false, length = 32)
  private String glAccountCode;

  @Column(name = "source_event_id", nullable = false, updatable = false)
  private UUID sourceEventId;

  protected LedgerPosting() {
    // for JPA
  }

  /**
   * Creates a revenue posting from a consumed sale (the {@code SaleRecorded} path). Equivalent to
   * {@link #LedgerPosting(PostingType, UUID, String, Money, String, UUID)} with {@link
   * PostingType#REVENUE}; kept so the existing revenue call site stays terse.
   *
   * @param businessId the originating business unit
   * @param period the accounting period {@code YYYY-MM} (use {@link #periodOf(Instant)})
   * @param amount the posting amount as {@link Money} (never a float)
   * @param glAccountCode the resolved revenue account (a {@code chart_of_account.account_code})
   * @param sourceEventId the consumed event's UUID — the idempotency key (UNIQUE in the schema)
   */
  public LedgerPosting(
      UUID businessId, String period, Money amount, String glAccountCode, UUID sourceEventId) {
    this(PostingType.REVENUE, businessId, period, amount, glAccountCode, sourceEventId);
  }

  /**
   * Creates a dimensional posting of any {@link PostingType} (REVENUE from {@code SaleRecorded},
   * EXPENSE from {@code ExpenseRecorded}).
   *
   * @param postingType the posting kind (drives the P&amp;L revenue-vs-expense split)
   * @param businessId the originating business unit
   * @param period the accounting period {@code YYYY-MM} (use {@link #periodOf(Instant)})
   * @param amount the posting amount as {@link Money} (never a float)
   * @param glAccountCode the resolved {@code chart_of_account.account_code} (the dimension)
   * @param sourceEventId the consumed event's UUID — the idempotency key (UNIQUE in the schema)
   */
  public LedgerPosting(
      PostingType postingType,
      UUID businessId,
      String period,
      Money amount,
      String glAccountCode,
      UUID sourceEventId) {
    this.id = UUID.randomUUID();
    this.postingType = Objects.requireNonNull(postingType, "postingType");
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.period = Objects.requireNonNull(period, "period");
    this.amount = MoneyEmbeddable.of(amount);
    this.glAccountCode = Objects.requireNonNull(glAccountCode, "glAccountCode");
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

  /** The resolved {@code chart_of_account} account code (the dimensional account). */
  public String getGlAccountCode() {
    return glAccountCode;
  }

  public UUID getSourceEventId() {
    return sourceEventId;
  }
}
