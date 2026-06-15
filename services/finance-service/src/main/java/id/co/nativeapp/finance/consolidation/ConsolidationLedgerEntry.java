package id.co.nativeapp.finance.consolidation;

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
 * The {@code consolidation_ledger} aggregate (P3d SEAM 3a) — one APPEND-ONLY consolidation posting:
 * an intercompany {@link ConsolidationEntryType#ELIMINATION} contra, an {@link
 * ConsolidationEntryType#ADJUSTMENT}, or a supersession {@link ConsolidationEntryType#REVERSAL}.
 * Mirrors the labor {@code ledger_posting} discipline (#23): the ledger is never mutated, only
 * appended; a higher {@code close_run_seq} reverses prior PRIMARY entries with a NEGATED amount and
 * a deterministic synthetic {@code source_entry_id}.
 *
 * <p><strong>Two-GUC conjunction RLS.</strong> Like every group table, this is scoped by BOTH the
 * bound consolidation group AND the bound LEAD-company tenant: {@code group_id = current_group AND
 * company_id = current_tenant}. It extends {@link Auditable}, so {@code company_id} carries the
 * group's LEAD company (rule 4); the close binds {@code (tenant = lead, group = group_id)} via
 * {@link id.co.nativeapp.tenant.TenantContext#callAsGroup} before any write.
 *
 * <p>The amount is a {@code libs/money} {@link Money} (rule 8 — integer minor units + ISO-4217
 * currency, never a float), persisted via {@link MoneyEmbeddable}. An ELIMINATION contra and every
 * REVERSAL carry a NEGATIVE amount. {@code source_entry_id} carries a {@code UNIQUE} backstop so a
 * re-run at the same {@code close_run_seq} (deriving identical synthetic ids) can never
 * double-post.
 */
@Entity
@Table(name = "consolidation_ledger")
public class ConsolidationLedgerEntry extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "group_id", nullable = false, updatable = false)
  private UUID groupId;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @Enumerated(EnumType.STRING)
  @Column(name = "entry_type", nullable = false, updatable = false, length = 16)
  private ConsolidationEntryType entryType;

  @Column(name = "gl_account_code", nullable = false, updatable = false, length = 32)
  private String glAccountCode;

  @Embedded private MoneyEmbeddable amount;

  /**
   * The intercompany reference this entry eliminates, or {@code null} for a non-intercompany row.
   */
  @Column(name = "intercompany_ref", updatable = false, length = 64)
  private String intercompanyRef;

  /**
   * The member company whose leg this entry eliminates/adjusts (a DIMENSION for traceability), or
   * {@code null}. Never the tenant — the owning tenant is the group's lead ({@code company_id}).
   */
  @Column(name = "source_member_company_id", updatable = false)
  private UUID sourceMemberCompanyId;

  @Enumerated(EnumType.STRING)
  @Column(name = "posting_role", nullable = false, updatable = false, length = 16)
  private ConsolidationPostingRole postingRole;

  @Column(name = "close_run_seq", nullable = false, updatable = false)
  private int closeRunSeq;

  @Column(name = "source_entry_id", nullable = false, updatable = false)
  private UUID sourceEntryId;

  protected ConsolidationLedgerEntry() {
    // for JPA
  }

  /**
   * Creates a consolidation ledger entry.
   *
   * @param groupId the consolidation group (the second RLS dimension)
   * @param period the accounting period {@code YYYY-MM}
   * @param entryType ELIMINATION | ADJUSTMENT | REVERSAL
   * @param glAccountCode the consolidation GL account this entry posts to
   * @param amount the entry amount as {@link Money} (negative for an elimination contra / REVERSAL)
   * @param intercompanyRef the intercompany reference this eliminates, or {@code null}
   * @param sourceMemberCompanyId the member whose leg this eliminates (a dimension), or {@code
   *     null}
   * @param postingRole PRIMARY for an original, REVERSAL for a supersession contra
   * @param closeRunSeq the close run that produced this entry
   * @param sourceEntryId the deterministic synthetic id (the UNIQUE idempotency key)
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public ConsolidationLedgerEntry(
      UUID groupId,
      String period,
      ConsolidationEntryType entryType,
      String glAccountCode,
      Money amount,
      String intercompanyRef,
      UUID sourceMemberCompanyId,
      ConsolidationPostingRole postingRole,
      int closeRunSeq,
      UUID sourceEntryId) {
    this.id = UUID.randomUUID();
    this.groupId = Objects.requireNonNull(groupId, "groupId");
    this.period = Objects.requireNonNull(period, "period");
    this.entryType = Objects.requireNonNull(entryType, "entryType");
    this.glAccountCode = Objects.requireNonNull(glAccountCode, "glAccountCode");
    this.amount = MoneyEmbeddable.of(amount);
    this.intercompanyRef = intercompanyRef;
    this.sourceMemberCompanyId = sourceMemberCompanyId;
    this.postingRole = Objects.requireNonNull(postingRole, "postingRole");
    this.closeRunSeq = closeRunSeq;
    this.sourceEntryId = Objects.requireNonNull(sourceEntryId, "sourceEntryId");
  }

  public UUID getId() {
    return id;
  }

  public UUID getGroupId() {
    return groupId;
  }

  public String getPeriod() {
    return period;
  }

  public ConsolidationEntryType getEntryType() {
    return entryType;
  }

  public String getGlAccountCode() {
    return glAccountCode;
  }

  /** The entry amount as a {@link Money} value (reconstructed from its columns). */
  public Money getAmount() {
    return amount.toMoney();
  }

  public String getIntercompanyRef() {
    return intercompanyRef;
  }

  public UUID getSourceMemberCompanyId() {
    return sourceMemberCompanyId;
  }

  public ConsolidationPostingRole getPostingRole() {
    return postingRole;
  }

  public int getCloseRunSeq() {
    return closeRunSeq;
  }

  public UUID getSourceEntryId() {
    return sourceEntryId;
  }
}
