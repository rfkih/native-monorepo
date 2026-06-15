package id.co.nativeapp.finance.grouptb;

import id.co.nativeapp.finance.mapping.AccountType;
import id.co.nativeapp.finance.revenue.MoneyEmbeddable;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code group_trial_balance} aggregate (P3d SEAM 2) — one trial-balance LINE a consolidation
 * group has ingested from a member's {@code TrialBalancePublished} event. The ingest target for
 * group consolidation; SEAM 2 stops at ingest (no consolidation/elimination math — that is SEAM 3).
 *
 * <p><strong>Two-GUC conjunction RLS.</strong> Unlike every single-tenant table (which is scoped by
 * {@code company_id} alone), a group table is scoped by BOTH the bound consolidation group AND the
 * bound LEAD-company tenant: the {@code group_trial_balance} policy is {@code group_id =
 * current_setting('app.current_group') AND company_id = current_setting('app.current_tenant')}. It
 * extends {@link Auditable}, so {@code company_id} is present and carries the group's LEAD company
 * (rule 4) — the row is owned by the lead, and a member never reads the group's accumulated trial
 * balance. The consumer binds {@code (tenant = lead, group = group_id)} via {@link
 * id.co.nativeapp.tenant.TenantContext#callAsGroup} before the write, so both GUCs are set and the
 * WITH CHECK passes; any partial binding fails closed.
 *
 * <p><strong>{@code member_company_id} is a DIMENSION, not the tenant.</strong> It records which
 * member's figures the line carries (the {@code TrialBalancePublished.company_id}); the owning
 * tenant is the lead. This is the whole point of the conjunction: the group's books live under the
 * lead, aggregating many members' lines, and no member can enumerate or mutate them.
 *
 * <p>The amount is a {@code libs/money} {@link Money} (rule 8 — integer minor units + ISO-4217
 * currency, never a float), persisted via {@link MoneyEmbeddable}. {@code source_event_id} + {@code
 * gl_account_code} + {@code posting_type} carry a {@code UNIQUE} backstop so a re-delivered event
 * can never double-insert a line — the database backstop behind the {@code ProcessedEventStore}
 * dedupe (rule 3).
 */
@Entity
@Table(name = "group_trial_balance")
public class GroupTrialBalanceLine extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "group_id", nullable = false, updatable = false)
  private UUID groupId;

  @Column(name = "member_company_id", nullable = false, updatable = false)
  private UUID memberCompanyId;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @Column(name = "gl_account_code", nullable = false, updatable = false, length = 32)
  private String glAccountCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "account_type", nullable = false, updatable = false, length = 16)
  private AccountType accountType;

  /**
   * The posting kind carried verbatim from the event. Kept as a {@code String} (not the 2-value
   * {@code PostingType} enum) because a trial balance spans the full chart — balance-sheet lines
   * carry posting kinds beyond REVENUE/EXPENSE — and SEAM 2 ingests the member's reported value as
   * is. The {@code account_type} above (the constrained five-class enum) is the dimension SEAM 3's
   * consolidation aggregates by.
   */
  @Column(name = "posting_type", nullable = false, updatable = false, length = 32)
  private String postingType;

  @Embedded private MoneyEmbeddable amount;

  /**
   * The intercompany counterparty for a related-party line, or {@code null}. SEAM-3 elimination.
   */
  @Column(name = "related_party_counterparty_id", updatable = false)
  private UUID relatedPartyCounterpartyId;

  /** A free-form intercompany reference for a related-party line, or {@code null}. */
  @Column(name = "intercompany_ref", updatable = false, length = 64)
  private String intercompanyRef;

  @Column(name = "uses_illustrative_rules", nullable = false, updatable = false)
  private boolean usesIllustrativeRules;

  @Column(name = "source_event_id", nullable = false, updatable = false)
  private UUID sourceEventId;

  protected GroupTrialBalanceLine() {
    // for JPA
  }

  /**
   * Creates a group trial-balance line ingested from a {@code TrialBalancePublished} member line.
   *
   * @param groupId the consolidation group (the second RLS dimension)
   * @param memberCompanyId the member company the figures came from (a dimension, not the tenant)
   * @param period the accounting period {@code YYYY-MM}
   * @param glAccountCode the resolved {@code chart_of_account} account code
   * @param accountType the GL account class (ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE)
   * @param postingType the posting kind
   * @param amount the line amount as {@link Money} (never a float)
   * @param relatedPartyCounterpartyId the intercompany counterparty, or {@code null}
   * @param intercompanyRef a free-form intercompany reference, or {@code null}
   * @param usesIllustrativeRules whether the figure is illustrative-placeholder-derived
   * @param sourceEventId the {@code TrialBalancePublished} event UUID (the idempotency key)
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public GroupTrialBalanceLine(
      UUID groupId,
      UUID memberCompanyId,
      String period,
      String glAccountCode,
      AccountType accountType,
      String postingType,
      Money amount,
      UUID relatedPartyCounterpartyId,
      String intercompanyRef,
      boolean usesIllustrativeRules,
      UUID sourceEventId) {
    this.id = UUID.randomUUID();
    this.groupId = Objects.requireNonNull(groupId, "groupId");
    this.memberCompanyId = Objects.requireNonNull(memberCompanyId, "memberCompanyId");
    this.period = Objects.requireNonNull(period, "period");
    this.glAccountCode = Objects.requireNonNull(glAccountCode, "glAccountCode");
    this.accountType = Objects.requireNonNull(accountType, "accountType");
    this.postingType = Objects.requireNonNull(postingType, "postingType");
    this.amount = MoneyEmbeddable.of(amount);
    this.relatedPartyCounterpartyId = relatedPartyCounterpartyId;
    this.intercompanyRef = intercompanyRef;
    this.usesIllustrativeRules = usesIllustrativeRules;
    this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
  }

  public UUID getId() {
    return id;
  }

  public UUID getGroupId() {
    return groupId;
  }

  public UUID getMemberCompanyId() {
    return memberCompanyId;
  }

  public String getPeriod() {
    return period;
  }

  public String getGlAccountCode() {
    return glAccountCode;
  }

  public AccountType getAccountType() {
    return accountType;
  }

  public String getPostingType() {
    return postingType;
  }

  /** The line amount as a {@link Money} value (reconstructed from its columns). */
  public Money getAmount() {
    return amount.toMoney();
  }

  /** The intercompany counterparty for a related-party line, or {@code null}. */
  public UUID getRelatedPartyCounterpartyId() {
    return relatedPartyCounterpartyId;
  }

  /** A free-form intercompany reference for a related-party line, or {@code null}. */
  public String getIntercompanyRef() {
    return intercompanyRef;
  }

  /** Whether the figure is derived from illustrative-placeholder statutory data. */
  public boolean isUsesIllustrativeRules() {
    return usesIllustrativeRules;
  }

  public UUID getSourceEventId() {
    return sourceEventId;
  }
}
