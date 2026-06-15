package id.co.nativeapp.finance;

import static id.co.nativeapp.finance.GroupCloseFixtures.expense;
import static id.co.nativeapp.finance.GroupCloseFixtures.intercompanyExpense;
import static id.co.nativeapp.finance.GroupCloseFixtures.intercompanyPayable;
import static id.co.nativeapp.finance.GroupCloseFixtures.intercompanyReceivable;
import static id.co.nativeapp.finance.GroupCloseFixtures.intercompanyRevenue;
import static id.co.nativeapp.finance.GroupCloseFixtures.revenue;
import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.consolidation.ConsolidationState;
import id.co.nativeapp.finance.consolidation.GroupCloseResult;
import id.co.nativeapp.finance.consolidation.GroupCloseService;
import id.co.nativeapp.finance.group.GroupReadModelService;
import id.co.nativeapp.finance.grouptb.GroupTrialBalanceIngestService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * P3d SEAM 3a — the SINGLE-CURRENCY group close. Drives the close pipeline directly (it is a
 * callable service this seam, not yet an authz endpoint) against the real Flyway migration + the
 * two-GUC conjunction RLS, under the unprivileged {@code app_user} (FORCE RLS binds even the owning
 * role). Proves: the same-currency sum + intercompany elimination, the membership effective-dating
 * gate, the member-completeness gate, the clean multi-currency rejection, the intercompany block,
 * supersession + idempotent re-run, and the collision-free reversal id.
 */
@SpringBootTest
class GroupCloseTest extends PostgresRlsTestBase {

  private static final String PERIOD = "2026-06";

  @Autowired private GroupReadModelService groupReadModel;
  @Autowired private GroupTrialBalanceIngestService ingestService;
  @Autowired private GroupCloseService closeService;

  private GroupCloseFixtures fx;

  @BeforeEach
  void setUpFixtures() {
    fx = new GroupCloseFixtures(groupReadModel, ingestService);
  }

  @Test
  void aSameCurrencyCloseSumsAndEliminatesInternalTradeToZeroPreservingExternalFigures() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));

    // Member A: external revenue 10M, external expense 4M, PLUS an internal SALE of 3M to B.
    fx.ingestTrialBalance(
        groupId,
        memberA,
        PERIOD,
        "IDR",
        List.of(
            revenue(10_000_000L, "IDR"),
            expense(4_000_000L, "IDR"),
            intercompanyRevenue(3_000_000L, "IDR", memberB, "IC-AB-001")));
    // Member B: external revenue 7M, external expense 2M, PLUS the matching internal PURCHASE of
    // 3M.
    fx.ingestTrialBalance(
        groupId,
        memberB,
        PERIOD,
        "IDR",
        List.of(
            revenue(7_000_000L, "IDR"),
            expense(2_000_000L, "IDR"),
            intercompanyExpense(3_000_000L, "IDR", memberA, "IC-AB-001")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.isClosed()).isTrue();
    // Gross revenue = 10M + 3M(IC) + 7M = 20M; internal trade 3M eliminated -> group revenue 17M.
    // Gross expense = 4M + 2M + 3M(IC) = 9M; internal 3M eliminated -> group expense 6M.
    // Net = 17M - 6M = 11M (the external-only result; internal trade nets to zero).
    assertThat(summaryMinorAsAdmin(groupId, "group_revenue_minor")).isEqualTo(17_000_000L);
    assertThat(summaryMinorAsAdmin(groupId, "group_expense_minor")).isEqualTo(6_000_000L);
    assertThat(summaryMinorAsAdmin(groupId, "group_net_minor")).isEqualTo(11_000_000L);

    // The eliminations are append-only PRIMARY entries (one revenue contra + one expense contra),
    // each a negative 3M.
    assertThat(ledgerCountAsAdmin(groupId)).isEqualTo(2L);
    assertThat(primaryEliminationSumAsAdmin(groupId))
        .isEqualTo(-6_000_000L); // -3M revenue + -3M exp
    // The intercompany reference reconciled MATCHED.
    assertThat(matchStateAsAdmin(groupId, "IC-AB-001")).isEqualTo("MATCHED");
  }

  @Test
  void theMembershipEffectiveDatingGateIncludesAndExcludesByPeriodEnd() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID activeMember = UUID.randomUUID();
    UUID leftBeforePeriodEnd = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    // active member: joined before, open-ended -> active at 2026-06-30.
    fx.addMember(groupId, activeMember, LocalDate.of(2026, 1, 1));
    // left member: window closes 2026-06-01, so at period-end 2026-06-30 it is NOT active
    // (half-open
    // effective_to is exclusive). It must be EXCLUDED from the close even though it has lines.
    fx.addMember(groupId, leftBeforePeriodEnd, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1));

    fx.ingestTrialBalance(
        groupId, activeMember, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));
    // The left member's stray lines must not be consolidated.
    fx.ingestTrialBalance(
        groupId, leftBeforePeriodEnd, PERIOD, "IDR", List.of(revenue(99_000_000L, "IDR")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    // CLOSED: the only active-at-period-end member is activeMember; the left member is excluded, so
    // its 99M does NOT inflate the group, and completeness passes on the active set alone.
    assertThat(result.isClosed()).isTrue();
    assertThat(summaryMinorAsAdmin(groupId, "group_revenue_minor")).isEqualTo(10_000_000L);
  }

  @Test
  void pendingMembersWhenAnActiveMemberHasNoIngestedTrialBalance() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));

    // Only A reports; B (active at period-end) has no trial balance -> PENDING_MEMBERS.
    fx.ingestTrialBalance(groupId, memberA, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.state()).isEqualTo(ConsolidationState.PENDING_MEMBERS);
    assertThat(summaryStateAsAdmin(groupId)).isEqualTo("PENDING_MEMBERS");
    // No eliminations posted on a held close.
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
  }

  @Test
  void anEmptyActiveMemberSetHoldsTheCloseRatherThanConsolidatingTheFullUnfilteredSet() {
    // FIX 3: with ZERO members active at period-end (a stale/lagging projection, or every member
    // having left), the close must FAIL CLOSED — HOLD rather than pass the full unfiltered line set
    // through and write CLOSED, which would bypass the active-at-period-end guarantee in exactly
    // the
    // stale case it protects. The group is "caught up" (its GroupDefined is consumed, no pending
    // marker), so the warming-up gate does NOT fire — this is the empty-active-set guard
    // specifically.
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID leftMember = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    // The only member left the group BEFORE period-end (window closes 2026-06-01, exclusive), so at
    // 2026-06-30 NO member is active. Its stray lines must NOT be consolidated.
    fx.addMember(groupId, leftMember, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1));
    fx.ingestTrialBalance(groupId, leftMember, PERIOD, "IDR", List.of(revenue(99_000_000L, "IDR")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    // HELD (PENDING_MEMBERS) — the empty active set never consolidates the unfiltered 99M.
    assertThat(result.state()).isEqualTo(ConsolidationState.PENDING_MEMBERS);
    assertThat(summaryStateAsAdmin(groupId)).isEqualTo("PENDING_MEMBERS");
    // Ledger empty, and NO CLOSED summary exists.
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
    assertThat(closedSummaryCountAsAdmin(groupId)).isZero();
  }

  @Test
  void aMultiCurrencyGroupIsTranslatedAndClosedUnderTheFlaggedSimplifiedPolicyNotRejected() {
    // SEAM 3b: a multi-currency group is no longer rejected MULTI_CURRENCY_UNSUPPORTED — it is
    // TRANSLATED into the reporting currency under the SIMPLIFIED, FLAGGED policy and CLOSED. Each
    // member's trial balance BALANCES in its own functional currency (so the only residual is the
    // FX effect of translating BS at CLOSING + P&L at AVERAGE), and that residual lands in the
    // flagged CTA reserve (3950). See GroupCloseMultiCurrencyTest for the full machinery; this is
    // the regression that the 3a hard stop is GONE.
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberIdr = UUID.randomUUID();
    UUID memberUsd = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberIdr, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberUsd, LocalDate.of(2026, 1, 1));

    // IDR member (reporting = IDR -> identity translation), balances: ASSET 6M = REVENUE 10M -
    // EXPENSE 4M.
    fx.ingestTrialBalance(
        groupId,
        memberIdr,
        PERIOD,
        "IDR",
        List.of(
            GroupCloseFixtures.asset(6_000_000L, "IDR"),
            revenue(10_000_000L, "IDR"),
            expense(4_000_000L, "IDR")));
    // USD member, balances in USD: ASSET $300 = REVENUE $500 - EXPENSE $200.
    fx.ingestTrialBalance(
        groupId,
        memberUsd,
        PERIOD,
        "USD",
        List.of(
            GroupCloseFixtures.asset(300_00L, "USD"),
            revenue(500_00L, "USD"),
            expense(200_00L, "USD")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    // CLOSED (flagged), NOT rejected. The retired MULTI_CURRENCY_UNSUPPORTED state is never
    // written.
    assertThat(result.isClosed()).isTrue();
    assertThat(summaryStateAsAdmin(groupId)).isEqualTo("CLOSED");
    assertThat(summaryFlagAsAdmin(groupId, "uses_simplified_translation_policy")).isTrue();
    assertThat(summaryFlagAsAdmin(groupId, "uses_stub_fx")).isTrue();
  }

  @Test
  void anUnmatchedIntercompanyLegBlocksTheClose() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));

    // A reports an internal sale to B, but B never reports the matching purchase -> UNMATCHED.
    fx.ingestTrialBalance(
        groupId,
        memberA,
        PERIOD,
        "IDR",
        List.of(
            revenue(10_000_000L, "IDR"),
            intercompanyRevenue(3_000_000L, "IDR", memberB, "IC-AB-LONELY")));
    fx.ingestTrialBalance(groupId, memberB, PERIOD, "IDR", List.of(revenue(7_000_000L, "IDR")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.state()).isEqualTo(ConsolidationState.INTERCOMPANY_UNRECONCILED);
    assertThat(matchStateAsAdmin(groupId, "IC-AB-LONELY")).isEqualTo("UNMATCHED");
    // Held back: NO eliminations posted (money never eliminated against nothing).
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
  }

  @Test
  void anAmountMismatchIntercompanyLegBlocksTheClose() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));

    // Both report the leg, but the magnitudes differ (3M vs 2.9M) -> AMOUNT_MISMATCH, blocks.
    fx.ingestTrialBalance(
        groupId,
        memberA,
        PERIOD,
        "IDR",
        List.of(intercompanyRevenue(3_000_000L, "IDR", memberB, "IC-AB-OFF")));
    fx.ingestTrialBalance(
        groupId,
        memberB,
        PERIOD,
        "IDR",
        List.of(intercompanyExpense(2_900_000L, "IDR", memberA, "IC-AB-OFF")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.state()).isEqualTo(ConsolidationState.INTERCOMPANY_UNRECONCILED);
    assertThat(matchStateAsAdmin(groupId, "IC-AB-OFF")).isEqualTo("AMOUNT_MISMATCH");
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
  }

  @Test
  void twoSameClassIntercompanyLegsBlockTheCloseRatherThanMisEliminating() {
    // FIX 1 (the critical): a same-class matched pair (two REVENUE legs, 3M each, on one ref) is
    // NOT a well-formed intercompany trade. Eliminating it would leave internal revenue un-removed
    // AND fabricate a phantom negative expense. The close must BLOCK (MALFORMED), not consolidate.
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));

    // Both members report a REVENUE leg on the same ref (NOT one sale + one purchase).
    fx.ingestTrialBalance(
        groupId,
        memberA,
        PERIOD,
        "IDR",
        List.of(intercompanyRevenue(3_000_000L, "IDR", memberB, "IC-BAD-SAMECLASS")));
    fx.ingestTrialBalance(
        groupId,
        memberB,
        PERIOD,
        "IDR",
        List.of(intercompanyRevenue(3_000_000L, "IDR", memberA, "IC-BAD-SAMECLASS")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.state()).isEqualTo(ConsolidationState.INTERCOMPANY_UNRECONCILED);
    assertThat(matchStateAsAdmin(groupId, "IC-BAD-SAMECLASS")).isEqualTo("MALFORMED");
    // Held back: NO eliminations posted, NO CLOSED summary (never silently mis-eliminated).
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
    assertThat(summaryStateAsAdmin(groupId)).isEqualTo("INTERCOMPANY_UNRECONCILED");
  }

  @Test
  void aSignFlippedRevenuePlusNegativeExpensePairBlocksRatherThanFabricatingNet() {
    // SECOND CRITICAL: the MATCH test must be sign-FAITHFUL, not sign-blind. A one-REVENUE(+3M) +
    // one-EXPENSE(-3M) pair has EQUAL ABS and OPPOSITE sign. Under a sign-blind abs-equality test
    // it
    // would pass one-rev+one-exp well-formedness AND abs-equality -> MATCHED, but the -3M sits in
    // gross expense AND the elimination subtracts another 3M from expense -> group expense
    // double-counts the negative -> fabricated net. The STRICT invariant (both legs amountMinor >
    // 0)
    // rejects the non-positive expense leg -> MALFORMED, so the close BLOCKS: no CLOSED summary, an
    // empty ledger, NO fabricated net.
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));

    // A: external revenue 10M + external expense 4M + an IC REVENUE leg of +3M.
    // B: external revenue 7M + external expense 2M + an IC EXPENSE leg of -3M (sign-flipped).
    // Sign-blind, this would fabricate net 14M instead of the correct 8M.
    fx.ingestTrialBalance(
        groupId,
        memberA,
        PERIOD,
        "IDR",
        List.of(
            revenue(10_000_000L, "IDR"),
            expense(4_000_000L, "IDR"),
            intercompanyRevenue(3_000_000L, "IDR", memberB, "IC-SIGN-FLIP")));
    fx.ingestTrialBalance(
        groupId,
        memberB,
        PERIOD,
        "IDR",
        List.of(
            revenue(7_000_000L, "IDR"),
            expense(2_000_000L, "IDR"),
            intercompanyExpense(-3_000_000L, "IDR", memberA, "IC-SIGN-FLIP")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.state()).isEqualTo(ConsolidationState.INTERCOMPANY_UNRECONCILED);
    assertThat(matchStateAsAdmin(groupId, "IC-SIGN-FLIP")).isEqualTo("MALFORMED");
    // Held back: NO eliminations posted, NO CLOSED summary, NO fabricated net.
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
    assertThat(closedSummaryCountAsAdmin(groupId)).isZero();
    assertThat(summaryStateAsAdmin(groupId)).isEqualTo("INTERCOMPANY_UNRECONCILED");
  }

  @Test
  void aPureBalanceSheetIntercompanyRefDoesNotBlockThePnlCloseAndIsNotEliminated() {
    // WARNING 1: 3a consolidates only the P&L. A pure balance-sheet IC ref (an IC receivable=ASSET
    // +
    // IC payable=LIABILITY pair, equal magnitude, between two members) does NOT affect the P&L net,
    // so it must be a NON-BLOCKING, out-of-scope SKIP (OUT_OF_SCOPE_BALANCE_SHEET): the close still
    // CLOSES on its P&L, and the balance-sheet ref is NOT eliminated (no contra posted).
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));

    // A: external revenue 10M + an IC RECEIVABLE (ASSET) of 5M from B.
    // B: external expense 2M + the matching IC PAYABLE (LIABILITY) of 5M to A.
    fx.ingestTrialBalance(
        groupId,
        memberA,
        PERIOD,
        "IDR",
        List.of(
            revenue(10_000_000L, "IDR"),
            intercompanyReceivable(5_000_000L, "IDR", memberB, "IC-BS-001")));
    fx.ingestTrialBalance(
        groupId,
        memberB,
        PERIOD,
        "IDR",
        List.of(
            expense(2_000_000L, "IDR"),
            intercompanyPayable(5_000_000L, "IDR", memberA, "IC-BS-001")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    // CLOSED: the balance-sheet IC ref is out of 3a scope, neither blocking nor eliminated.
    assertThat(result.isClosed()).isTrue();
    assertThat(matchStateAsAdmin(groupId, "IC-BS-001")).isEqualTo("OUT_OF_SCOPE_BALANCE_SHEET");
    // P&L is the external figures only — the ASSET/LIABILITY legs do not roll into revenue/expense.
    assertThat(summaryMinorAsAdmin(groupId, "group_revenue_minor")).isEqualTo(10_000_000L);
    assertThat(summaryMinorAsAdmin(groupId, "group_expense_minor")).isEqualTo(2_000_000L);
    assertThat(summaryMinorAsAdmin(groupId, "group_net_minor")).isEqualTo(8_000_000L);
    // NO elimination contra posted for the out-of-scope balance-sheet ref.
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
  }

  @Test
  void aWellFormedRevenuePlusExpensePairNetsInternalTradeToZeroPreservingExternalFigures() {
    // The companion to the same-class block: the PROPER one-revenue+one-expense pair still nets the
    // internal trade to zero and preserves the external figures (eliminate-by-class is correct).
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));

    // A: external revenue 8M + an internal SALE of 3M to B. B: external expense 1M + the matching
    // internal PURCHASE of 3M from A.
    fx.ingestTrialBalance(
        groupId,
        memberA,
        PERIOD,
        "IDR",
        List.of(
            revenue(8_000_000L, "IDR"),
            intercompanyRevenue(3_000_000L, "IDR", memberB, "IC-AB-OK")));
    fx.ingestTrialBalance(
        groupId,
        memberB,
        PERIOD,
        "IDR",
        List.of(
            expense(1_000_000L, "IDR"),
            intercompanyExpense(3_000_000L, "IDR", memberA, "IC-AB-OK")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.isClosed()).isTrue();
    assertThat(matchStateAsAdmin(groupId, "IC-AB-OK")).isEqualTo("MATCHED");
    // Gross revenue = 8M + 3M(IC) = 11M; eliminate 3M -> 8M. Gross expense = 1M + 3M(IC) = 4M;
    // eliminate 3M -> 1M. Net = 8M - 1M = 7M (external-only; internal 3M trade nets to zero).
    assertThat(summaryMinorAsAdmin(groupId, "group_revenue_minor")).isEqualTo(8_000_000L);
    assertThat(summaryMinorAsAdmin(groupId, "group_expense_minor")).isEqualTo(1_000_000L);
    assertThat(summaryMinorAsAdmin(groupId, "group_net_minor")).isEqualTo(7_000_000L);
    // One revenue contra (-3M) + one expense contra (-3M).
    assertThat(primaryEliminationSumAsAdmin(groupId)).isEqualTo(-6_000_000L);
  }

  @Test
  void aThreeLegIntercompanyRefBlocksTheCloseRatherThanTruncating() {
    // FIX 2(a): a ref with MORE THAN TWO legs is not well-formed. Truncating to the first two and
    // eliminating would leave the third leg's money summed into gross but un-eliminated AND
    // un-flagged. The close must BLOCK (MALFORMED).
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();
    UUID memberC = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberC, LocalDate.of(2026, 1, 1));

    // Three members all tag the same ref -> three legs.
    fx.ingestTrialBalance(
        groupId,
        memberA,
        PERIOD,
        "IDR",
        List.of(intercompanyRevenue(3_000_000L, "IDR", memberB, "IC-TRIPLE")));
    fx.ingestTrialBalance(
        groupId,
        memberB,
        PERIOD,
        "IDR",
        List.of(intercompanyExpense(3_000_000L, "IDR", memberA, "IC-TRIPLE")));
    fx.ingestTrialBalance(
        groupId,
        memberC,
        PERIOD,
        "IDR",
        List.of(intercompanyExpense(3_000_000L, "IDR", memberA, "IC-TRIPLE")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.state()).isEqualTo(ConsolidationState.INTERCOMPANY_UNRECONCILED);
    assertThat(matchStateAsAdmin(groupId, "IC-TRIPLE")).isEqualTo("MALFORMED");
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
  }

  @Test
  void aSelfDealOneMemberTwoLegsOnTheSameRefBlocksTheClose() {
    // FIX 2(b): a matched pair must require member_a != member_b. One member emitting two lines
    // with
    // the same ref is a self-deal, not an intercompany trade — block it.
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));

    // One member emits BOTH a revenue and an expense leg on the same ref (same member_company_id).
    fx.ingestTrialBalance(
        groupId,
        memberA,
        PERIOD,
        "IDR",
        List.of(
            intercompanyRevenue(3_000_000L, "IDR", memberA, "IC-SELF"),
            intercompanyExpense(3_000_000L, "IDR", memberA, "IC-SELF")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.state()).isEqualTo(ConsolidationState.INTERCOMPANY_UNRECONCILED);
    assertThat(matchStateAsAdmin(groupId, "IC-SELF")).isEqualTo("MALFORMED");
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
  }

  @Test
  void theIllustrativeFlagIsCarriedStickyOrFromMemberTrialBalances() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));

    fx.ingestTrialBalance(groupId, memberA, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));
    // B's trial balance is illustrative-placeholder-derived -> the group summary is flagged.
    fx.ingestIllustrativeTrialBalance(
        groupId, memberB, PERIOD, "IDR", List.of(revenue(5_000_000L, "IDR")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.isClosed()).isTrue();
    assertThat(summaryFlagAsAdmin(groupId, "uses_illustrative_rules")).isTrue();
    // 3a writes no FX, so uses_stub_fx is always false.
    assertThat(summaryFlagAsAdmin(groupId, "uses_stub_fx")).isFalse();
  }

  // ----------------------------------------------------------------------- admin inspection
  // helpers

  private long summaryMinorAsAdmin(UUID groupId, String column) {
    return longQueryAsAdmin(
        "SELECT "
            + column
            + " FROM consolidation_summary WHERE group_id = '"
            + groupId
            + "' AND state <> 'SUPERSEDED'");
  }

  private String summaryStateAsAdmin(UUID groupId) {
    return stringQueryAsAdmin(
        "SELECT state FROM consolidation_summary WHERE group_id = '" + groupId + "'");
  }

  private boolean summaryFlagAsAdmin(UUID groupId, String column) {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT "
                    + column
                    + " FROM consolidation_summary WHERE group_id = '"
                    + groupId
                    + "' AND state <> 'SUPERSEDED'")) {
      rs.next();
      return rs.getBoolean(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private long ledgerCountAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT count(*) FROM consolidation_ledger WHERE group_id = '" + groupId + "'");
  }

  private long closedSummaryCountAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT count(*) FROM consolidation_summary WHERE group_id = '"
            + groupId
            + "' AND state = 'CLOSED'");
  }

  private long primaryEliminationSumAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT coalesce(sum(amount_minor),0) FROM consolidation_ledger WHERE group_id = '"
            + groupId
            + "' AND posting_role = 'PRIMARY'");
  }

  private String matchStateAsAdmin(UUID groupId, String ref) {
    return stringQueryAsAdmin(
        "SELECT state FROM intercompany_match WHERE group_id = '"
            + groupId
            + "' AND intercompany_ref = '"
            + ref
            + "'");
  }

  private long longQueryAsAdmin(String sql) {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String stringQueryAsAdmin(String sql) {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
