package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.assets.domain.AssetAlreadyDisposedException;
import id.co.nativeapp.finance.assets.domain.AssetDepreciationBehindException;
import id.co.nativeapp.finance.assets.domain.AssetNotFoundException;
import id.co.nativeapp.finance.assets.domain.IdempotencyKeyConflictException;
import id.co.nativeapp.finance.assets.dto.AssetResponse;
import id.co.nativeapp.finance.assets.service.AmortizationRunWriter;
import id.co.nativeapp.finance.assets.service.AssetDisposalWriter;
import id.co.nativeapp.finance.assets.service.AssetReader;
import id.co.nativeapp.finance.assets.service.DisposeAssetResult;
import id.co.nativeapp.finance.assets.service.FixedAssetWriter;
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
 * End-to-end integration test (real PostgreSQL, unprivileged {@code app_user}) for asset disposal
 * (ADR 0022): dispose-at-a-gain right after acquiring (no depreciation due yet), the idempotent
 * replay, the one-shot 409, the run exclusion of a DISPOSED asset, the depreciation-in-step guard
 * pair (behind + overshoot), a loss disposal after a posted month, the cash-flow reclassification
 * (pure capex + a single proceeds inflow, gain/loss backed out of operating, exact reconciliation),
 * and RLS isolation.
 */
@SpringBootTest
class AssetDisposalLifecycleTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "ffffffff-ffff-ffff-ffff-aaaaaaaaaa88";
  private static final String TENANT_B = "ffffffff-ffff-ffff-ffff-bbbbbbbbbb99";
  private static final String ACTOR = "disposal@integration.co.id";

  @Autowired private FixedAssetWriter fixedAssetWriter;
  @Autowired private AssetDisposalWriter assetDisposalWriter;
  @Autowired private AmortizationRunWriter amortizationRunWriter;
  @Autowired private AssetReader assetReader;
  @Autowired private GlTrialBalanceReader glTrialBalanceReader;
  @Autowired private CashFlowReader cashFlowReader;
  @Autowired private Clock clock;

  @Test
  void disposeAtAGainRightAfterAcquiringEndToEnd() throws Exception {
    String thisPeriod = LedgerPosting.periodOf(clock.instant());
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);

    // Acquire 12,000,000 / 12 months (depreciation starts NEXT month — none due yet) and sell the
    // same month for 13,000,000 → gain 1,000,000 (no depreciation, book value == cost).
    UUID assetId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                fixedAssetWriter
                    .acquire("Delivery van", today, 12_000_000L, 0L, 12, "IDR", "disp-acq-1")
                    .assetId());
    DisposeAssetResult disposed =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> assetDisposalWriter.dispose(assetId, today, 13_000_000L, "IDR", "disp-e2e-1"));
    assertThat(disposed.created()).isTrue();
    assertThat(disposed.assetId()).isEqualTo(assetId);

    // The GL derecognized the asset and recorded the gain: 1500 nets to zero (capex in, disposal
    // out), the clearing account holds the net cash effect (−12M capex + 13M proceeds), 4200 holds
    // the gain credit-side.
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          assertThat(debitNet(thisPeriod, "1500")).isZero();
          assertThat(debitNet(thisPeriod, "1900")).isEqualTo(1_000_000L);
          assertThat(debitNet(thisPeriod, "4200")).isEqualTo(-1_000_000L); // credit-side gain
          assertThat(debitNet(thisPeriod, "1590")).isZero(); // nothing accumulated
          return null;
        });

    // A dispose RETRY with the same Idempotency-Key replays the original — posts nothing.
    DisposeAssetResult replay =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> assetDisposalWriter.dispose(assetId, today, 13_000_000L, "IDR", "disp-e2e-1"));
    assertThat(replay.created()).isFalse();
    assertThat(replay.assetId()).isEqualTo(assetId);
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          assertThat(debitNet(thisPeriod, "4200"))
              .as("replay must not double-post")
              .isEqualTo(-1_000_000L);
          return null;
        });

    // A SECOND disposal attempt (different key) is a one-shot conflict.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR,
                    () ->
                        assetDisposalWriter.dispose(
                            assetId, today, 9_000_000L, "IDR", "disp-e2e-other")))
        .isInstanceOf(AssetAlreadyDisposedException.class);

    // The register shows the disposal: DISPOSED, proceeds, zero book value (off the books).
    List<AssetResponse> register =
        TenantContext.callAs(TENANT_A, ACTOR, () -> assetReader.register());
    assertThat(register).hasSize(1);
    AssetResponse row = register.getFirst();
    assertThat(row.status()).isEqualTo("DISPOSED");
    assertThat(row.disposalDate()).isEqualTo(today);
    assertThat(row.proceedsMinor()).isEqualTo(13_000_000L);
    assertThat(row.bookValueMinor()).isZero();

    // The NEXT month's run excludes the disposed asset — no depreciation ever posts for it.
    String nextPeriod =
        YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)).plusMonths(1).toString();
    TenantContext.callAs(TENANT_A, ACTOR, () -> amortizationRunWriter.run(nextPeriod));
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          assertThat(debitNet(nextPeriod, "5500")).isZero();
          return null;
        });

    // Cash flow for the disposal period: pure capex −12M under INVESTING plus ONE proceeds inflow
    // +13M; the gain is backed out of operating; the statement reconciles exactly.
    CashFlowResponse cashFlow =
        TenantContext.callAs(TENANT_A, ACTOR, () -> cashFlowReader.read(thisPeriod).orElseThrow());
    assertThat(cashFlow.investingLines())
        .anySatisfy(
            l -> {
              assertThat(l.accountCode()).isEqualTo("1500");
              assertThat(l.amountMinor()).isEqualTo(-12_000_000L);
            })
        .anySatisfy(
            l -> {
              assertThat(l.accountCode()).isEqualTo(CashFlowReader.DISPOSAL_PROCEEDS_CODE);
              assertThat(l.amountMinor()).isEqualTo(13_000_000L);
            });
    assertThat(cashFlow.cashFromInvestingMinor()).isEqualTo(1_000_000L);
    assertThat(cashFlow.operatingLines())
        .anySatisfy(
            l -> {
              assertThat(l.accountCode()).isEqualTo("4200");
              assertThat(l.amountMinor()).isEqualTo(-1_000_000L); // the gain, backed out
            });
    assertThat(cashFlow.cashFromOperatingMinor()).isZero();
    assertThat(cashFlow.reconciled()).isTrue();

    // RLS: tenant B sees nothing and cannot dispose A's asset (indistinguishable from absent).
    assertThat(TenantContext.callAs(TENANT_B, ACTOR, () -> assetReader.register())).isEmpty();
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B,
                    ACTOR,
                    () ->
                        assetDisposalWriter.dispose(
                            assetId, today, 13_000_000L, "IDR", "disp-e2e-b")))
        .isInstanceOf(AssetNotFoundException.class);
  }

  @Test
  void theDepreciationInStepGuardsAndALossDisposalAfterAPostedMonth() throws Exception {
    String thisPeriod = LedgerPosting.periodOf(clock.instant());
    String priorPeriod =
        YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)).minusMonths(1).toString();
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    LocalDate twoMonthsAgo = today.minusMonths(2);

    // Two assets acquired two months ago → depreciation started LAST month (month 1 due then).
    UUID keepRunning =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                fixedAssetWriter
                    .acquire("Oven", twoMonthsAgo, 12_000_000L, 0L, 12, "IDR", "disp-acq-2")
                    .assetId());
    UUID toDispose =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                fixedAssetWriter
                    .acquire("Mixer", twoMonthsAgo, 12_000_000L, 0L, 12, "IDR", "disp-acq-3")
                    .assetId());

    // BEHIND: month 1 (last month) was never run → disposing now is rejected.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR,
                    () ->
                        assetDisposalWriter.dispose(
                            toDispose, today, 10_000_000L, "IDR", "disp-e2e-2")))
        .isInstanceOf(AssetDepreciationBehindException.class);

    // Catch up: run LAST month (posts month 1 = 1,000,000 for each asset).
    TenantContext.callAs(TENANT_A, ACTOR, () -> amortizationRunWriter.run(priorPeriod));

    // Now the loss disposal succeeds: book = 12M − 1M = 11M, sold for 10M → loss 1,000,000, and
    // the frozen accumulated is exactly the posted month.
    DisposeAssetResult disposed =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> assetDisposalWriter.dispose(toDispose, today, 10_000_000L, "IDR", "disp-e2e-3"));
    assertThat(disposed.created()).isTrue();
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          assertThat(debitNet(thisPeriod, "5600")).isEqualTo(1_000_000L); // Dr loss
          return null;
        });

    // Reusing the DISPOSED asset's key against a different asset is a 409 (path id authoritative,
    // code-review W1) — the other asset's disposal must not silently replay as this one's.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR,
                    () ->
                        assetDisposalWriter.dispose(
                            keepRunning, today, 10_000_000L, "IDR", "disp-e2e-3")))
        .isInstanceOf(IdempotencyKeyConflictException.class);

    // This month's run still posts the OTHER asset's month 2, but nothing for the disposed one.
    TenantContext.callAs(TENANT_A, ACTOR, () -> amortizationRunWriter.run(thisPeriod));
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          assertThat(debitNet(thisPeriod, "5500")).isEqualTo(1_000_000L); // keepRunning only
          return null;
        });

    // OVERSHOOT: keepRunning now has a posted month IN this period → disposing it is rejected
    // (would need a reversal).
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR,
                    () ->
                        assetDisposalWriter.dispose(
                            keepRunning, today, 10_000_000L, "IDR", "disp-e2e-4")))
        .isInstanceOf(AssetDepreciationBehindException.class);
    assertThat(
            TenantContext.callAs(TENANT_A, ACTOR, () -> assetReader.register()).stream()
                .filter(a -> a.id().equals(keepRunning))
                .findFirst()
                .orElseThrow()
                .status())
        .isEqualTo("ACTIVE");

    // Cash flow: capex −24M (two acquisitions, disposal credit stripped) + proceeds +10M under
    // INVESTING; the loss backed out of operating alongside the pure depreciation add-back;
    // reconciles exactly.
    CashFlowResponse cashFlow =
        TenantContext.callAs(TENANT_A, ACTOR, () -> cashFlowReader.read(thisPeriod).orElseThrow());
    assertThat(cashFlow.investingLines())
        .anySatisfy(
            l -> {
              assertThat(l.accountCode()).isEqualTo("1500");
              assertThat(l.amountMinor()).isEqualTo(-24_000_000L);
            })
        .anySatisfy(
            l -> {
              assertThat(l.accountCode()).isEqualTo(CashFlowReader.DISPOSAL_PROCEEDS_CODE);
              assertThat(l.amountMinor()).isEqualTo(10_000_000L);
            });
    assertThat(cashFlow.cashFromInvestingMinor()).isEqualTo(-14_000_000L);
    assertThat(cashFlow.operatingLines())
        .anySatisfy(
            l -> {
              assertThat(l.accountCode()).isEqualTo("5600");
              assertThat(l.amountMinor()).isEqualTo(1_000_000L); // the loss, added back
            })
        .anySatisfy(
            l -> {
              assertThat(l.accountCode()).isEqualTo("1590");
              assertThat(l.amountMinor()).isEqualTo(1_000_000L); // the PURE depreciation add-back
            });
    assertThat(cashFlow.cashFromOperatingMinor()).isZero();
    assertThat(cashFlow.reconciled()).isTrue();
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
