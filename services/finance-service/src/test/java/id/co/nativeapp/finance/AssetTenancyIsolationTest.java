package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.assets.domain.DeferralKind;
import id.co.nativeapp.finance.assets.dto.AssetResponse;
import id.co.nativeapp.finance.assets.service.AmortizationRunWriter;
import id.co.nativeapp.finance.assets.service.AssetReader;
import id.co.nativeapp.finance.assets.service.DeferralReader;
import id.co.nativeapp.finance.assets.service.DeferralWriter;
import id.co.nativeapp.finance.assets.service.FixedAssetWriter;
import id.co.nativeapp.finance.assets.service.RunAmortizationResult;
import id.co.nativeapp.finance.gl.service.GlTrialBalanceReader;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.statements.dto.CashFlowResponse;
import id.co.nativeapp.finance.statements.service.CashFlowReader;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end integration test (real PostgreSQL, unprivileged {@code app_user}) for the Phase 6
 * asset/deferral flow: acquire an asset (capex posts, start = next month) + create a prepaid
 * expense and a deferred revenue (opening entries post, start = this month) → run THIS month
 * (prepaid + deferred-revenue shares post; the asset is not yet due) → run NEXT month (the asset's
 * first depreciation joins) → registers show accumulated/book/remaining; a re-run is an idempotent
 * no-op; the cash-flow statement classifies the capex as INVESTING and still reconciles exactly;
 * RLS isolates everything from a second tenant.
 */
@SpringBootTest
class AssetTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "ffffffff-ffff-ffff-ffff-aaaaaaaaaa66";
  private static final String TENANT_B = "ffffffff-ffff-ffff-ffff-bbbbbbbbbb77";
  private static final String ACTOR = "assets@integration.co.id";

  @Autowired private FixedAssetWriter fixedAssetWriter;
  @Autowired private DeferralWriter deferralWriter;
  @Autowired private AmortizationRunWriter amortizationRunWriter;
  @Autowired private AssetReader assetReader;
  @Autowired private DeferralReader deferralReader;
  @Autowired private GlTrialBalanceReader glTrialBalanceReader;
  @Autowired private CashFlowReader cashFlowReader;
  @Autowired private Clock clock;

  @Test
  void acquireDepreciateAndAmortizeEndToEnd() throws Exception {
    String thisPeriod = LedgerPosting.periodOf(clock.instant());
    String nextPeriod =
        YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)).plusMonths(1).toString();
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);

    // Acquire a 12,000,000 / 0-salvage / 12-month asset (starts NEXT month) + a 6,000,000 / 6-month
    // prepaid and a 3,000,000 / 3-month deferred revenue (both start THIS month).
    UUID assetId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> {
              UUID id =
                  fixedAssetWriter
                      .acquire("Espresso machine", today, 12_000_000L, 0L, 12, "IDR", "acq-e2e-1")
                      .assetId();
              deferralWriter.create(
                  DeferralKind.PREPAID_EXPENSE,
                  "A year's rent",
                  6_000_000L,
                  6,
                  thisPeriod,
                  "IDR",
                  "def-e2e-1");
              deferralWriter.create(
                  DeferralKind.DEFERRED_REVENUE,
                  "Subscription",
                  3_000_000L,
                  3,
                  thisPeriod,
                  "IDR",
                  "def-e2e-2");
              return id;
            });

    // An acquire RETRY with the same Idempotency-Key replays the original — no second capex (C-1).
    var replay =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                fixedAssetWriter.acquire(
                    "Espresso machine", today, 12_000_000L, 0L, 12, "IDR", "acq-e2e-1"));
    assertThat(replay.created()).isFalse();
    assertThat(replay.assetId()).isEqualTo(assetId);

    // The opening postings landed: 1500 capex, 1400 prepaid, 2400 deferred revenue.
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          assertThat(debitNet(thisPeriod, "1500")).isEqualTo(12_000_000L);
          assertThat(debitNet(thisPeriod, "1400")).isEqualTo(6_000_000L);
          assertThat(debitNet(thisPeriod, "2400")).isEqualTo(-3_000_000L); // credit-side
          return null;
        });

    // Run THIS month: the two deferrals are due (month 1); the asset is not yet.
    RunAmortizationResult firstRun =
        TenantContext.callAs(TENANT_A, ACTOR, () -> amortizationRunWriter.run(thisPeriod));
    assertThat(firstRun.created()).isTrue();
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          assertThat(debitNet(thisPeriod, "5000")).isEqualTo(1_000_000L); // prepaid amortized
          assertThat(debitNet(thisPeriod, "1400")).isEqualTo(5_000_000L); // 6,000,000 − 1,000,000
          assertThat(debitNet(thisPeriod, "2400")).isEqualTo(-2_000_000L); // 1,000,000 recognized
          assertThat(debitNet(thisPeriod, "5500")).isZero(); // no depreciation yet
          return null;
        });

    // A new item may NOT start in an already-run month (code-review W-1): that month is sealed and
    // never re-runs, so the item could never fully amortize — rejected up front.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR,
                    () ->
                        deferralWriter.create(
                            DeferralKind.PREPAID_EXPENSE,
                            "Backdated",
                            1_200_000L,
                            12,
                            thisPeriod,
                            "IDR",
                            "def-e2e-3")))
        .isInstanceOf(IllegalArgumentException.class);

    // A re-run of THIS month is an idempotent no-op (same run id, nothing re-posted).
    RunAmortizationResult rerun =
        TenantContext.callAs(TENANT_A, ACTOR, () -> amortizationRunWriter.run(thisPeriod));
    assertThat(rerun.created()).isFalse();
    assertThat(rerun.runId()).isEqualTo(firstRun.runId());
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          assertThat(debitNet(thisPeriod, "5000"))
              .as("re-run must not double-post")
              .isEqualTo(1_000_000L);
          return null;
        });

    // Run NEXT month: the asset's first depreciation month joins (12,000,000 / 12 = 1,000,000).
    RunAmortizationResult secondRun =
        TenantContext.callAs(TENANT_A, ACTOR, () -> amortizationRunWriter.run(nextPeriod));
    assertThat(secondRun.created()).isTrue();
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          assertThat(debitNet(nextPeriod, "5500")).isEqualTo(1_000_000L); // Dr dep expense
          assertThat(debitNet(nextPeriod, "1590")).isEqualTo(-1_000_000L); // Cr accumulated
          return null;
        });

    // The registers roll up the sub-ledger: book value + remaining balances.
    List<AssetResponse> register =
        TenantContext.callAs(TENANT_A, ACTOR, () -> assetReader.register());
    assertThat(register).hasSize(1);
    assertThat(register.getFirst().id()).isEqualTo(assetId);
    assertThat(register.getFirst().accumulatedMinor()).isEqualTo(1_000_000L);
    assertThat(register.getFirst().bookValueMinor()).isEqualTo(11_000_000L);
    var deferrals = TenantContext.callAs(TENANT_A, ACTOR, () -> deferralReader.list());
    assertThat(deferrals).hasSize(2);
    assertThat(
            deferrals.stream()
                .filter(d -> d.kind().equals("PREPAID_EXPENSE"))
                .findFirst()
                .orElseThrow()
                .remainingMinor())
        .isEqualTo(4_000_000L); // 6,000,000 − 2 months
    assertThat(
            deferrals.stream()
                .filter(d -> d.kind().equals("DEFERRED_REVENUE"))
                .findFirst()
                .orElseThrow()
                .remainingMinor())
        .isEqualTo(1_000_000L); // 3,000,000 − 2 months

    // The cash-flow statement classifies the capex under INVESTING and still reconciles exactly.
    CashFlowResponse cashFlow =
        TenantContext.callAs(TENANT_A, ACTOR, () -> cashFlowReader.read(thisPeriod).orElseThrow());
    assertThat(cashFlow.investingLines())
        .anySatisfy(
            l -> {
              assertThat(l.accountCode()).isEqualTo("1500");
              assertThat(l.amountMinor()).isEqualTo(-12_000_000L); // capex outflow
            });
    assertThat(cashFlow.reconciled()).isTrue();
    assertThat(cashFlow.netChangeInCashMinor()).isEqualTo(cashFlow.cashMovementMinor());

    // RLS: tenant B sees nothing.
    assertThat(TenantContext.callAs(TENANT_B, ACTOR, () -> assetReader.register())).isEmpty();
    assertThat(TenantContext.callAs(TENANT_B, ACTOR, () -> deferralReader.list())).isEmpty();
  }

  /** The debit-net ({@code Σdebit − Σcredit}) of {@code accountCode} in {@code period}'s GL. */
  private long debitNet(String period, String accountCode) {
    return glTrialBalanceReader.read(period).stream()
        .filter(l -> l.getAccountCode().equals(accountCode))
        .findFirst()
        .map(l -> l.getTotalDebitMinor() - l.getTotalCreditMinor())
        .orElse(0L);
  }
}
