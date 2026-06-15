package id.co.nativeapp.finance.pnl.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code consolidated_pnl} read model — one incrementally-accumulated row per {@code
 * (company_id, period, currency)} holding the period's revenue and expense, from which the P&amp;L
 * net (revenue − expense) is derived on read. The dashboard P&amp;L query reads it.
 *
 * <p>As each {@link id.co.nativeapp.finance.revenue.domain.LedgerPosting} lands, its amount is
 * added onto the matching leg ({@code revenue_minor} or {@code expense_minor}) in the SAME
 * transaction via the atomic upsert in {@link PnlReadModelWriter}, so the read model is always
 * consistent with the ledger (CQRS: post on write, this is the aggregate-on-read projection,
 * maintained eagerly so the read is a single-row lookup).
 *
 * <p>It extends {@link Auditable} and is under RLS: an <em>application-maintained</em> read model
 * (not a Debezium-CDC-derived projection), so the rule-4 Auditable and rule-5 RLS guarantees apply
 * uniformly — a tenant only ever sees its own P&amp;L.
 *
 * <p>Both legs are {@code BIGINT} minor units against one ISO-4217 {@code currency} (rule 8 — never
 * a float); {@link #net()} / {@link #revenue()} / {@link #expense()} reconstruct {@code libs/money}
 * {@link Money}. Net uses {@code Money} subtraction, which refuses to mix currencies. The mapping
 * is read-only here (the write path is the atomic upsert), so it exposes no JPA mutators.
 */
@Entity
@Table(name = "consolidated_pnl")
public class ConsolidatedPnl extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @Column(name = "revenue_minor", nullable = false)
  private long revenueMinor;

  @Column(name = "expense_minor", nullable = false)
  private long expenseMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  /**
   * Whether ANY labor cost contributing to this period's P&amp;L is derived from {@code
   * ILLUSTRATIVE_PLACEHOLDER} statutory data (#23). STICKY/monotonic: the {@link
   * PnlReadModelWriter} upsert ORs it in, so once a placeholder-derived run lands the period stays
   * flagged true (a later non-illustrative or reversal posting cannot clear it) — the consolidated
   * figure is contaminated and a dashboard must keep badging the period provisional. {@code false}
   * for a period built only from revenue / {@code ExpenseRecorded}.
   */
  @Column(name = "uses_illustrative_rules", nullable = false)
  private boolean usesIllustrativeRules;

  protected ConsolidatedPnl() {
    // for JPA
  }

  public UUID getId() {
    return id;
  }

  public String getPeriod() {
    return period;
  }

  /** The accumulated revenue total as {@link Money}. */
  public Money revenue() {
    return Money.ofMinor(revenueMinor, currency.strip());
  }

  /** The accumulated expense total as {@link Money}. */
  public Money expense() {
    return Money.ofMinor(expenseMinor, currency.strip());
  }

  /** The P&amp;L net (revenue − expense) as {@link Money}, derived from the two legs. */
  public Money net() {
    return revenue().minus(expense());
  }

  /**
   * Whether this period's figure is contaminated by illustrative-placeholder labor data (sticky —
   * see the field doc). The dashboard renders a provisional/illustrative badge when {@code true}
   * and must not present the number as final (HR integrity, #23).
   */
  public boolean usesIllustrativeRules() {
    return usesIllustrativeRules;
  }
}
