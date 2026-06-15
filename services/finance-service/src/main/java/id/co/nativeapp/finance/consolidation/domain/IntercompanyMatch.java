package id.co.nativeapp.finance.consolidation.domain;

import id.co.nativeapp.finance.mapping.domain.AccountType;
import id.co.nativeapp.money.Money;
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
 * The {@code intercompany_match} aggregate (P3d SEAM 3a + 3b) — the reconciliation of ONE
 * intercompany reference for a period: which two members' related-party legs pair on {@code
 * (intercompany_ref, period)}, their amounts, and the {@link IntercompanyMatchState} — one of the
 * SIX states {@code MATCHED | UNMATCHED | AMOUNT_MISMATCH | MALFORMED | OUT_OF_SCOPE_BALANCE_SHEET
 * | MATCHED_CROSS_CURRENCY}.
 *
 * <p>A {@link IntercompanyMatchState#MATCHED} reference drives an ELIMINATION in the {@code
 * consolidation_ledger} (the internal trade nets to zero). A MATCHED reference is STRICTLY
 * well-formed by construction: EXACTLY one {@code REVENUE}-typed leg + one {@code EXPENSE}-typed
 * leg, BOTH amounts STRICTLY POSITIVE ({@code amountMinor > 0}), of EQUAL magnitude, between TWO
 * DIFFERENT members. An {@link IntercompanyMatchState#UNMATCHED} (only one member reported the
 * leg), {@link IntercompanyMatchState#AMOUNT_MISMATCH} (legs of unequal magnitude), or {@link
 * IntercompanyMatchState#MALFORMED} (more than two legs, a self-deal, not a one-revenue+one-expense
 * pair, a ZERO/NEGATIVE leg amount, or a P&amp;L+balance-sheet mix) BLOCKS the close — money is
 * never eliminated against nothing, nor eliminated against the wrong account class, nor against a
 * sign-blind match that would fabricate net. An {@link
 * IntercompanyMatchState#OUT_OF_SCOPE_BALANCE_SHEET} reference (both legs are balance-sheet account
 * types) is a NON-BLOCKING, out-of-3a-scope skip: 3a consolidates only the P&amp;L, so a pure
 * balance-sheet IC ref does not affect the net and is neither eliminated nor blocking.
 *
 * <p>{@code accountTypeA} / {@code accountTypeB} record each leg's GL account class so the close
 * can eliminate the REVENUE leg amount from gross revenue and the EXPENSE leg amount from gross
 * expense SEPARATELY (for a well-formed MATCHED pair they are equal, so group net is unchanged).
 *
 * <p>{@code amount_a} is always present (the reference would not exist without at least one leg);
 * {@code amount_b} and {@code member_b} are {@code null} for an UNMATCHED reference. Money is
 * integer minor units + ISO-4217 (rule 8). Under the two-GUC conjunction RLS; {@code company_id} is
 * the group's LEAD (rule 4).
 */
@Entity
@Table(name = "intercompany_match")
public class IntercompanyMatch extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "group_id", nullable = false, updatable = false)
  private UUID groupId;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @Column(name = "intercompany_ref", nullable = false, updatable = false, length = 64)
  private String intercompanyRef;

  @Column(name = "member_a", nullable = false, updatable = false)
  private UUID memberA;

  @Column(name = "member_b", updatable = false)
  private UUID memberB;

  @Column(name = "amount_a_minor", nullable = false, updatable = false)
  private long amountAMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "amount_a_currency", nullable = false, updatable = false, length = 3)
  private String amountACurrency;

  @Column(name = "amount_b_minor", updatable = false)
  private Long amountBMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "amount_b_currency", updatable = false, length = 3)
  private String amountBCurrency;

  @Enumerated(EnumType.STRING)
  @Column(name = "account_type_a", nullable = false, updatable = false, length = 16)
  private AccountType accountTypeA;

  @Enumerated(EnumType.STRING)
  @Column(name = "account_type_b", updatable = false, length = 16)
  private AccountType accountTypeB;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, updatable = false, length = 32)
  private IntercompanyMatchState state;

  @Column(name = "close_run_seq", nullable = false, updatable = false)
  private int closeRunSeq;

  protected IntercompanyMatch() {
    // for JPA
  }

  /**
   * Creates an intercompany reconciliation row.
   *
   * @param groupId the consolidation group
   * @param period the accounting period {@code YYYY-MM}
   * @param intercompanyRef the intercompany reference being reconciled
   * @param memberA the first member's company id (always present)
   * @param memberB the counterparty member's company id, or {@code null} for UNMATCHED
   * @param amountA the first member's leg amount (always present)
   * @param amountB the counterparty leg amount, or {@code null} for UNMATCHED
   * @param accountTypeA the first leg's GL account class (always present)
   * @param accountTypeB the counterparty leg's GL account class, or {@code null} for UNMATCHED
   * @param state MATCHED | UNMATCHED | AMOUNT_MISMATCH | MALFORMED
   * @param closeRunSeq the close run this reconciliation belongs to
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public IntercompanyMatch(
      UUID groupId,
      String period,
      String intercompanyRef,
      UUID memberA,
      UUID memberB,
      Money amountA,
      Money amountB,
      AccountType accountTypeA,
      AccountType accountTypeB,
      IntercompanyMatchState state,
      int closeRunSeq) {
    Objects.requireNonNull(amountA, "amountA");
    this.id = UUID.randomUUID();
    this.groupId = Objects.requireNonNull(groupId, "groupId");
    this.period = Objects.requireNonNull(period, "period");
    this.intercompanyRef = Objects.requireNonNull(intercompanyRef, "intercompanyRef");
    this.memberA = Objects.requireNonNull(memberA, "memberA");
    this.memberB = memberB;
    this.amountAMinor = amountA.amountMinor();
    this.amountACurrency = amountA.currency().getCurrencyCode();
    this.amountBMinor = amountB == null ? null : amountB.amountMinor();
    this.amountBCurrency = amountB == null ? null : amountB.currency().getCurrencyCode();
    this.accountTypeA = Objects.requireNonNull(accountTypeA, "accountTypeA");
    this.accountTypeB = accountTypeB;
    this.state = Objects.requireNonNull(state, "state");
    this.closeRunSeq = closeRunSeq;
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

  public String getIntercompanyRef() {
    return intercompanyRef;
  }

  public UUID getMemberA() {
    return memberA;
  }

  public UUID getMemberB() {
    return memberB;
  }

  /** The first member's leg amount as {@link Money}. */
  public Money amountA() {
    return Money.ofMinor(amountAMinor, amountACurrency.strip());
  }

  /** The counterparty leg amount as {@link Money}, or {@code null} for an UNMATCHED reference. */
  public Money amountB() {
    return amountBMinor == null ? null : Money.ofMinor(amountBMinor, amountBCurrency.strip());
  }

  /** The first leg's GL account class (always present). */
  public AccountType getAccountTypeA() {
    return accountTypeA;
  }

  /** The counterparty leg's GL account class, or {@code null} for an UNMATCHED reference. */
  public AccountType getAccountTypeB() {
    return accountTypeB;
  }

  public IntercompanyMatchState getState() {
    return state;
  }

  public int getCloseRunSeq() {
    return closeRunSeq;
  }

  /**
   * Whether this reference blocks the close. Everything other than a well-formed MATCHED pair
   * blocks EXCEPT {@link IntercompanyMatchState#OUT_OF_SCOPE_BALANCE_SHEET} (a pure balance-sheet
   * IC ref does not affect the P&amp;L net) and {@link
   * IntercompanyMatchState#MATCHED_CROSS_CURRENCY} (a well-formed cross-currency P&amp;L pair the
   * SEAM-3b translation path eliminates) — both non-blocking, so a non-blocking ref never makes the
   * group un-closable.
   */
  public boolean blocksClose() {
    return state != IntercompanyMatchState.MATCHED
        && state != IntercompanyMatchState.OUT_OF_SCOPE_BALANCE_SHEET
        && state != IntercompanyMatchState.MATCHED_CROSS_CURRENCY;
  }

  /**
   * Whether this reference drives a SAME-CURRENCY elimination contra (only a strictly well-formed
   * MATCHED same-currency P&amp;L pair does). A {@link
   * IntercompanyMatchState#MATCHED_CROSS_CURRENCY} pair is eliminated via the SEAM-3b
   * cross-currency translation path (translated legs + an FX_TRANSLATION_DIFF residual), NOT here;
   * an {@link IntercompanyMatchState#OUT_OF_SCOPE_BALANCE_SHEET} skip is recorded but NEVER
   * eliminated.
   */
  public boolean eliminates() {
    return state == IntercompanyMatchState.MATCHED;
  }

  /** Whether this reference is a well-formed CROSS-currency P&amp;L pair (SEAM 3b). */
  public boolean isCrossCurrency() {
    return state == IntercompanyMatchState.MATCHED_CROSS_CURRENCY;
  }

  /**
   * The amount of this match's REVENUE leg (the internal sale) to eliminate from gross revenue.
   * Only meaningful for an ELIMINATING pair ({@link IntercompanyMatchState#MATCHED} or {@link
   * IntercompanyMatchState#MATCHED_CROSS_CURRENCY}), where the pair is strictly well-formed
   * (exactly one revenue + one expense leg, both strictly positive) so this is guaranteed strictly
   * positive. Asserts that precondition so a future off-happy-path caller fails LOUD rather than
   * silently reading {@code amountB} (which may be {@code null} for an UNMATCHED reference, or the
   * wrong class for a MALFORMED one).
   */
  public Money revenueLegAmount() {
    requireEliminatingPair("revenueLegAmount");
    return accountTypeA == AccountType.REVENUE ? amountA() : amountB();
  }

  /**
   * The amount of this match's EXPENSE leg (the internal purchase) to eliminate from gross expense.
   * Only meaningful for an ELIMINATING pair ({@link IntercompanyMatchState#MATCHED} or {@link
   * IntercompanyMatchState#MATCHED_CROSS_CURRENCY}), where the pair is strictly well-formed
   * (exactly one revenue + one expense leg, both strictly positive) so this is guaranteed strictly
   * positive. Asserts that precondition so a future off-happy-path caller fails LOUD rather than
   * silently reading {@code amountB} (which may be {@code null} for an UNMATCHED reference, or the
   * wrong class for a MALFORMED one).
   */
  public Money expenseLegAmount() {
    requireEliminatingPair("expenseLegAmount");
    return accountTypeA == AccountType.EXPENSE ? amountA() : amountB();
  }

  /**
   * Asserts this match is an ELIMINATING pair (a {@code MATCHED} same-currency pair or a {@code
   * MATCHED_CROSS_CURRENCY} pair) — the only states for which a revenue/expense leg amount is
   * well-defined. Off any of those states the leg accessors would read {@code amountB} blindly
   * (null for UNMATCHED, the wrong class for MALFORMED); this makes such a misuse fail loud.
   */
  private void requireEliminatingPair(String accessor) {
    if (state != IntercompanyMatchState.MATCHED
        && state != IntercompanyMatchState.MATCHED_CROSS_CURRENCY) {
      throw new IllegalStateException(
          accessor
              + "() is only defined for an eliminating pair (MATCHED / MATCHED_CROSS_CURRENCY), not "
              + state
              + " (ref="
              + intercompanyRef
              + ")");
    }
  }

  /**
   * The member company that owns this match's REVENUE leg — the source for the revenue contra's
   * per-leg traceability. Only meaningful for a MATCHED pair.
   */
  public UUID revenueLegMember() {
    return accountTypeA == AccountType.REVENUE ? memberA : memberB;
  }

  /**
   * The member company that owns this match's EXPENSE leg — the source for the expense contra's
   * per-leg traceability. Only meaningful for a MATCHED pair.
   */
  public UUID expenseLegMember() {
    return accountTypeA == AccountType.EXPENSE ? memberA : memberB;
  }
}
