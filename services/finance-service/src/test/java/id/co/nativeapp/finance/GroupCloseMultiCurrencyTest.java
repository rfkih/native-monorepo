package id.co.nativeapp.finance;

import static id.co.nativeapp.finance.GroupCloseFixtures.asset;
import static id.co.nativeapp.finance.GroupCloseFixtures.equity;
import static id.co.nativeapp.finance.GroupCloseFixtures.expense;
import static id.co.nativeapp.finance.GroupCloseFixtures.intercompanyExpense;
import static id.co.nativeapp.finance.GroupCloseFixtures.intercompanyRevenue;
import static id.co.nativeapp.finance.GroupCloseFixtures.revenue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.consolidation.domain.ConsolidationState;
import id.co.nativeapp.finance.consolidation.dto.GroupCloseResult;
import id.co.nativeapp.finance.consolidation.service.GroupCloseService;
import id.co.nativeapp.finance.fx.domain.MissingFxRateException;
import id.co.nativeapp.finance.group.service.GroupReadModelService;
import id.co.nativeapp.finance.grouptb.service.GroupTrialBalanceIngestService;
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
 * P3d SEAM 3b — the MULTI-CURRENCY group close, FLAGGED-SIMPLIFIED. Multi-currency is SUPPORTED via
 * the translation path (the 3a {@code MULTI_CURRENCY_UNSUPPORTED} hard stop is GONE): each member
 * trial-balance line is translated into the reporting currency by account type (BS at CLOSING, P&L
 * at AVERAGE) via the shipped P3c {@code TranslationService}; the translated set balances after the
 * residual posts to the flagged CTA reserve (3950); the summary is flagged {@code
 * uses_simplified_translation_policy} + {@code uses_stub_fx}. A missing rate fails the whole close
 * atomically; a cross-currency intercompany pair eliminates with a flagged FX_TRANSLATION_DIFF;
 * supersession still works. A same-currency close is unaffected (flags false, no CTA).
 *
 * <p>The seeded stub rates (V4) are 1 USD = 16,000 IDR (CLOSING) and 1 USD = 15,900 IDR (AVERAGE).
 */
@SpringBootTest
class GroupCloseMultiCurrencyTest extends PostgresRlsTestBase {

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
  void aTwoMemberMultiCurrencyGroupTranslatesBalancesViaTheCtaAndIsFlaggedProvisional() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberIdr = UUID.randomUUID();
    UUID memberUsd = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberIdr, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberUsd, LocalDate.of(2026, 1, 1));

    // IDR member (reporting = IDR -> identity translation). Balances: ASSET 6M (debit) = REVENUE
    // 10M
    // (credit) - EXPENSE 4M (debit). Signed residual 0; contributes nothing to the CTA.
    fx.ingestTrialBalance(
        groupId,
        memberIdr,
        PERIOD,
        "IDR",
        List.of(asset(6_000_000L, "IDR"), revenue(10_000_000L, "IDR"), expense(4_000_000L, "IDR")));

    // USD member (functional USD, reporting IDR). Balances in USD: ASSET $300 = REVENUE $500 -
    // EXPENSE $200. Translated to IDR: REVENUE/EXPENSE at AVERAGE (15,900), ASSET at CLOSING
    // (16,000):
    //   revenue $500 -> 500 * 15,900       = 7,950,000 IDR
    //   expense $200 -> 200 * 15,900       = 3,180,000 IDR
    //   asset   $300 -> 300 * 16,000       = 4,800,000 IDR
    // Signed translated residual = ASSET(+4,800,000) + EXPENSE(+3,180,000) - REVENUE(7,950,000)
    //   = +30,000 IDR. The CTA posts -30,000 so the translated set reconciles to zero.
    fx.ingestTrialBalance(
        groupId,
        memberUsd,
        PERIOD,
        "USD",
        List.of(asset(300_00L, "USD"), revenue(500_00L, "USD"), expense(200_00L, "USD")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.isClosed()).isTrue();
    // group revenue = 10,000,000 (IDR) + 7,950,000 (USD->IDR avg) = 17,950,000
    // group expense =  4,000,000 (IDR) + 3,180,000 (USD->IDR avg) =  7,180,000
    // group net     = 10,770,000
    assertThat(summaryMinorAsAdmin(groupId, "group_revenue_minor")).isEqualTo(17_950_000L);
    assertThat(summaryMinorAsAdmin(groupId, "group_expense_minor")).isEqualTo(7_180_000L);
    assertThat(summaryMinorAsAdmin(groupId, "group_net_minor")).isEqualTo(10_770_000L);

    // The simplified-translation flags are loud (PROVISIONAL): translation happened + a stub rate
    // was used.
    assertThat(summaryFlagAsAdmin(groupId, "uses_simplified_translation_policy")).isTrue();
    assertThat(summaryFlagAsAdmin(groupId, "uses_stub_fx")).isTrue();

    // The translation residual posted to the FLAGGED CTA reserve account, balancing the set.
    assertThat(ctaSumAsAdmin(groupId)).isEqualTo(-30_000L);
    assertThat(ledgerSumForAccountAsAdmin(groupId, "3950-CTA-TRANSLATION-RESERVE"))
        .isEqualTo(-30_000L);
    // Exactly one CTA entry, no eliminations / FX diff (no intercompany here).
    assertThat(entryTypeCountAsAdmin(groupId, "CTA")).isEqualTo(1L);
    assertThat(entryTypeCountAsAdmin(groupId, "ELIMINATION")).isZero();
    assertThat(entryTypeCountAsAdmin(groupId, "FX_TRANSLATION_DIFF")).isZero();

    // The CTA is the GENUINE closing-vs-average FX effect on the (natively-balanced) USD book, NOT
    // an arbitrary plug: the only currency-translated member balances natively in USD (ASSET $300 =
    // REVENUE $500 - EXPENSE $200), so the ONLY translated residual is the rate spread between the
    // ASSET (CLOSING 16,000) and the P&L (AVERAGE 15,900):
    //   ASSET   $300 * (16,000 - 15,900) = +30,000  (the BS-vs-P&L rate-spread share)
    // i.e. the CTA magnitude EQUALS the closing-vs-average difference on the balanced book, here
    // 30,000 IDR. (Were the native book NOT balanced, the gate would have HELD before any CTA.)
    long expectedFxEffect = 300_00L * (16_000L - 15_900L) / 100L; // $300 in minor * spread / 100
    assertThat(-ctaSumAsAdmin(groupId)).isEqualTo(expectedFxEffect).isEqualTo(30_000L);

    // INTEGRITY GATE (self-contained admin-side recompute, NOT the x+(-x) tautology): independently
    // recompute the signed translated trial balance from the KNOWN translated amounts and add the
    // CTA the close actually posted (read from the ledger). On a validated-balanced input this is
    // 0.
    //   signed translated TB = ASSET(+4,800,000) + EXPENSE(+3,180,000) - REVENUE(7,950,000) =
    // +30,000
    long translatedSignedTb = 4_800_000L + 3_180_000L - 7_950_000L;
    assertThat(translatedSignedTb + ctaSumAsAdmin(groupId)).isZero();
  }

  @Test
  void aSingleCurrencyGroupIsUnaffectedNoTranslationNoCtaFlagsFalse() {
    // A same-currency (3a) close must stay byte-identical: no translation, no CTA, both 3b flags
    // false.
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));

    fx.ingestTrialBalance(groupId, memberA, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));
    fx.ingestTrialBalance(groupId, memberB, PERIOD, "IDR", List.of(expense(2_000_000L, "IDR")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.isClosed()).isTrue();
    assertThat(summaryFlagAsAdmin(groupId, "uses_simplified_translation_policy")).isFalse();
    assertThat(summaryFlagAsAdmin(groupId, "uses_stub_fx")).isFalse();
    // A same-currency close performs NO translation, so the balance-check + CTA path is skipped
    // entirely: no CTA and no FX_TRANSLATION_DIFF entries (the 3a model never carried a balancing
    // trial-balance residual). The ledger is empty here (no intercompany either).
    assertThat(entryTypeCountAsAdmin(groupId, "CTA")).isZero();
    assertThat(entryTypeCountAsAdmin(groupId, "FX_TRANSLATION_DIFF")).isZero();
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
  }

  @Test
  void aMissingRateFailsTheWholeCloseAtomicallyNoSummaryNoPartialLedger() {
    // A member in EUR (reporting IDR) has no seeded EUR->IDR rate, and the USD pivot fails too
    // (no EUR->USD seeded). The translation throws MissingFxRateException, which propagates out of
    // the @Transactional close and rolls the WHOLE thing back: NO summary, NO partial ledger.
    // Both members balance NATIVELY (so the native balance gate passes and the close reaches
    // translation, which is where the missing EUR rate fails) — the point under test is the
    // missing-rate atomicity, not the balance gate.
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberIdr = UUID.randomUUID();
    UUID memberEur = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberIdr, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberEur, LocalDate.of(2026, 1, 1));

    // IDR member balances natively (REVENUE 10M = EXPENSE 10M); identity translation, no rate
    // needed.
    fx.ingestTrialBalance(
        groupId,
        memberIdr,
        PERIOD,
        "IDR",
        List.of(revenue(10_000_000L, "IDR"), expense(10_000_000L, "IDR")));
    // EUR member balances natively (REVENUE 500 = EXPENSE 500) but has NO EUR->IDR rate -> the
    // translation of its first EUR line throws MissingFxRateException.
    fx.ingestTrialBalance(
        groupId,
        memberEur,
        PERIOD,
        "EUR",
        List.of(revenue(500_00L, "EUR"), expense(500_00L, "EUR")));

    assertThatThrownBy(() -> closeService.close(groupId, PERIOD, 1))
        .isInstanceOf(MissingFxRateException.class);

    // Atomic: the rolled-back transaction left NO summary and NO ledger rows for the group.
    assertThat(anySummaryCountAsAdmin(groupId)).isZero();
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
  }

  @Test
  void aCrossCurrencyIntercompanyPairEliminatesWithAnFxTranslationDiffAndNetsWithoutLosingMoney() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberIdr = UUID.randomUUID();
    UUID memberUsd = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberIdr, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberUsd, LocalDate.of(2026, 1, 1));

    // Cross-currency IC trade on one ref: the IDR member sells (IC REVENUE 3,180,000 IDR), the USD
    // member buys (IC EXPENSE $201). Each member's TB balances in its own currency via a balancing
    // BS line.
    //   IDR member: IC revenue 3,180,000 (credit) + ASSET 3,180,000 (debit) -> signed 0 (identity).
    //   USD member: IC expense $201 (debit)       + EQUITY $201 (credit)    -> signed 0 (native).
    // Translated (USD): EXPENSE@AVERAGE 201*15,900 = 3,195,900 ; EQUITY@CLOSING 201*16,000 =
    //   3,216,000 -> signed residual = +3,195,900 - 3,216,000 = -20,100 -> CTA +20,100.
    // Cross-currency elimination: revenueLeg(translated)=3,180,000, expenseLeg(translated)=
    //   3,195,900 -> FX_TRANSLATION_DIFF residual = 3,180,000 - 3,195,900 = -15,900.
    fx.ingestTrialBalance(
        groupId,
        memberIdr,
        PERIOD,
        "IDR",
        List.of(
            intercompanyRevenue(3_180_000L, "IDR", memberUsd, "IC-XCCY-001"),
            asset(3_180_000L, "IDR")));
    fx.ingestTrialBalance(
        groupId,
        memberUsd,
        PERIOD,
        "USD",
        List.of(
            intercompanyExpense(201_00L, "USD", memberIdr, "IC-XCCY-001"), equity(201_00L, "USD")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.isClosed()).isTrue();
    assertThat(matchStateAsAdmin(groupId, "IC-XCCY-001")).isEqualTo("MATCHED_CROSS_CURRENCY");

    // The internal trade is fully eliminated from BOTH classes: group revenue and expense net to 0
    // (the translated revenue leg leaves gross revenue, the translated expense leg leaves gross
    // expense). No money is lost — the FX residual between the two translated legs is CAPTURED as a
    // flagged FX_TRANSLATION_DIFF, not silently absorbed.
    assertThat(summaryMinorAsAdmin(groupId, "group_revenue_minor")).isZero();
    assertThat(summaryMinorAsAdmin(groupId, "group_expense_minor")).isZero();

    assertThat(entryTypeCountAsAdmin(groupId, "FX_TRANSLATION_DIFF")).isEqualTo(1L);
    assertThat(ledgerSumForAccountAsAdmin(groupId, "3960-FX-TRANSLATION-DIFF")).isEqualTo(-15_900L);
    // The two elimination contras (negated translated legs) + the CTA also posted.
    assertThat(eliminationSumAsAdmin(groupId)).isEqualTo(-(3_180_000L + 3_195_900L));
    assertThat(ctaSumAsAdmin(groupId)).isEqualTo(20_100L);
    // Flagged provisional.
    assertThat(summaryFlagAsAdmin(groupId, "uses_simplified_translation_policy")).isTrue();

    // INTEGRITY GATE (self-contained admin-side recompute): both members balance natively, so the
    // translated residual is purely the FX effect. signed translated TB:
    //   IDR member: ASSET(+3,180,000) - IC REVENUE(3,180,000)            =        0  (identity)
    //   USD member: EXPENSE@AVG(+3,195,900) - EQUITY@CLOSING(3,216,000)  =  -20,100  (FX spread)
    // total = -20,100; CTA posted = +20,100; sum reconciles to zero.
    long translatedSignedTb = (3_180_000L - 3_180_000L) + (3_195_900L - 3_216_000L);
    assertThat(translatedSignedTb + ctaSumAsAdmin(groupId)).isZero();
  }

  @Test
  void supersessionWorksForAMultiCurrencyClose() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberIdr = UUID.randomUUID();
    UUID memberUsd = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberIdr, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberUsd, LocalDate.of(2026, 1, 1));

    fx.ingestTrialBalance(
        groupId,
        memberIdr,
        PERIOD,
        "IDR",
        List.of(asset(6_000_000L, "IDR"), revenue(10_000_000L, "IDR"), expense(4_000_000L, "IDR")));
    fx.ingestTrialBalance(
        groupId,
        memberUsd,
        PERIOD,
        "USD",
        List.of(asset(300_00L, "USD"), revenue(500_00L, "USD"), expense(200_00L, "USD")));

    GroupCloseResult run1 = closeService.close(groupId, PERIOD, 1);
    assertThat(run1.isClosed()).isTrue();
    // Run 1 posts exactly one PRIMARY (the CTA).
    assertThat(primaryCountAsAdmin(groupId)).isEqualTo(1L);

    // Run 2 (higher seq) reverses run 1's CTA append-only and reposts a fresh CTA, supersedes run
    // 1.
    GroupCloseResult run2 = closeService.close(groupId, PERIOD, 2);
    assertThat(run2.isClosed()).isTrue();

    // 1 PRIMARY (run1 CTA) + 1 REVERSAL (of it) + 1 PRIMARY (run2 CTA) = 2 PRIMARY, 1 REVERSAL.
    assertThat(primaryCountAsAdmin(groupId)).isEqualTo(2L);
    assertThat(reversalCountAsAdmin(groupId)).isEqualTo(1L);
    // The CTA nets to run 2's value only: run1 (-30,000) + reversal (+30,000) + run2 (-30,000) =
    // -30,000.
    assertThat(ledgerNetAsAdmin(groupId)).isEqualTo(-30_000L);

    assertThat(summaryStateForSeqAsAdmin(groupId, 1)).isEqualTo("SUPERSEDED");
    assertThat(summaryStateForSeqAsAdmin(groupId, 2)).isEqualTo("CLOSED");
    // The active summary still carries the simplified-translation flag.
    assertThat(summaryFlagAsAdmin(groupId, "uses_simplified_translation_policy")).isTrue();
  }

  @Test
  void anUnbalancedMemberNativeTrialBalanceHoldsTheCloseWithNoCtaSweep() {
    // THE CRITICAL: a member whose books do NOT balance in its OWN functional currency must HOLD —
    // its raw native imbalance must NOT be swept into the CTA and mis-labelled a translation
    // adjustment, then written CLOSED. The native balance gate runs BEFORE translation.
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberIdr = UUID.randomUUID();
    UUID memberUsd = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberIdr, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberUsd, LocalDate.of(2026, 1, 1));

    // IDR member balances natively (ASSET 6M = REVENUE 10M - EXPENSE 4M).
    fx.ingestTrialBalance(
        groupId,
        memberIdr,
        PERIOD,
        "IDR",
        List.of(asset(6_000_000L, "IDR"), revenue(10_000_000L, "IDR"), expense(4_000_000L, "IDR")));
    // USD member does NOT balance natively: ASSET $300 + EXPENSE $200 - REVENUE $499 -> signed +$1
    // (a $1 hole). Pre-fix this $1 would translate to a non-zero residual and be swept into the
    // CTA;
    // the gate must HOLD instead.
    fx.ingestTrialBalance(
        groupId,
        memberUsd,
        PERIOD,
        "USD",
        List.of(asset(300_00L, "USD"), revenue(499_00L, "USD"), expense(200_00L, "USD")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    // HELD MEMBER_TRIAL_BALANCE_UNBALANCED — NOT CLOSED, NO summary figures, NO CTA sweep, empty
    // ledger.
    assertThat(result.isClosed()).isFalse();
    assertThat(result.state()).isEqualTo(ConsolidationState.MEMBER_TRIAL_BALANCE_UNBALANCED);
    assertThat(summaryStateAsAdmin(groupId)).isEqualTo("MEMBER_TRIAL_BALANCE_UNBALANCED");
    assertThat(entryTypeCountAsAdmin(groupId, "CTA")).isZero();
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
  }

  @Test
  void aMemberPublishedReconciledFalseHoldsTheCloseNotConsolidated() {
    // A member that published reconciled=false says its own books do not balance: HOLD, like the
    // completeness gate — even if its lines happen to sum signed-to-zero, the producer flagged it
    // unreconciled, so it must not consolidate.
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberIdr = UUID.randomUUID();
    UUID memberUsd = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberIdr, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberUsd, LocalDate.of(2026, 1, 1));

    fx.ingestTrialBalance(
        groupId,
        memberIdr,
        PERIOD,
        "IDR",
        List.of(asset(6_000_000L, "IDR"), revenue(10_000_000L, "IDR"), expense(4_000_000L, "IDR")));
    // USD member's lines BALANCE natively ($300 = $500 - $200) but it is published
    // reconciled=false.
    fx.ingestTrialBalance(
        groupId,
        memberUsd,
        PERIOD,
        "USD",
        false,
        List.of(asset(300_00L, "USD"), revenue(500_00L, "USD"), expense(200_00L, "USD")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);

    assertThat(result.isClosed()).isFalse();
    assertThat(result.state()).isEqualTo(ConsolidationState.MEMBER_TRIAL_BALANCE_UNBALANCED);
    assertThat(summaryStateAsAdmin(groupId)).isEqualTo("MEMBER_TRIAL_BALANCE_UNBALANCED");
    assertThat(entryTypeCountAsAdmin(groupId, "CTA")).isZero();
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
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
        "SELECT state FROM consolidation_summary WHERE group_id = '"
            + groupId
            + "' AND state <> 'SUPERSEDED'");
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

  private long anySummaryCountAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT count(*) FROM consolidation_summary WHERE group_id = '" + groupId + "'");
  }

  private long entryTypeCountAsAdmin(UUID groupId, String entryType) {
    return longQueryAsAdmin(
        "SELECT count(*) FROM consolidation_ledger WHERE group_id = '"
            + groupId
            + "' AND entry_type = '"
            + entryType
            + "'");
  }

  private long ctaSumAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT coalesce(sum(amount_minor),0) FROM consolidation_ledger WHERE group_id = '"
            + groupId
            + "' AND entry_type = 'CTA' AND posting_role = 'PRIMARY'");
  }

  private long eliminationSumAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT coalesce(sum(amount_minor),0) FROM consolidation_ledger WHERE group_id = '"
            + groupId
            + "' AND entry_type = 'ELIMINATION' AND posting_role = 'PRIMARY'");
  }

  private long ledgerSumForAccountAsAdmin(UUID groupId, String account) {
    return longQueryAsAdmin(
        "SELECT coalesce(sum(amount_minor),0) FROM consolidation_ledger WHERE group_id = '"
            + groupId
            + "' AND gl_account_code = '"
            + account
            + "' AND posting_role = 'PRIMARY'");
  }

  private long primaryCountAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT count(*) FROM consolidation_ledger WHERE group_id = '"
            + groupId
            + "' AND posting_role = 'PRIMARY'");
  }

  private long reversalCountAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT count(*) FROM consolidation_ledger WHERE group_id = '"
            + groupId
            + "' AND posting_role = 'REVERSAL'");
  }

  private long ledgerNetAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT coalesce(sum(amount_minor),0) FROM consolidation_ledger WHERE group_id = '"
            + groupId
            + "'");
  }

  private String summaryStateForSeqAsAdmin(UUID groupId, int seq) {
    return stringQueryAsAdmin(
        "SELECT state FROM consolidation_summary WHERE group_id = '"
            + groupId
            + "' AND close_run_seq = "
            + seq);
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
