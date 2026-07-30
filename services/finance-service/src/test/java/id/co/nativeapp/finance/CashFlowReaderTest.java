package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.projection.GlTrialBalanceLineView;
import id.co.nativeapp.finance.gl.service.GlTrialBalanceReader;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.statements.dto.CashFlowResponse;
import id.co.nativeapp.finance.statements.service.CashFlowReader;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link CashFlowReader} — the indirect-method cash-flow derivation, with a mocked
 * {@link GlTrialBalanceReader} feeding a controlled trial-balance line set and a mocked {@link
 * RoleAccountResolver} supplying the cash-account codes (no Spring/DB). Verifies net income, the
 * working-capital adjustments (AR up uses cash, AP up provides cash), the net change in cash, and
 * the exact reconciliation to the cash-account movement — plus that a non-reconciling (unbalanced)
 * set is rejected by the reader's assert.
 */
class CashFlowReaderTest {

  private static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");
  private static final String PERIOD = "2026-07";

  private GlTrialBalanceReader tb;
  private JdbcTemplate jdbc;
  private CashFlowReader reader;

  @BeforeEach
  void setUp() throws Exception {
    tb = mock(GlTrialBalanceReader.class);
    jdbc = mock(JdbcTemplate.class);
    RoleAccountResolver resolver = mock(RoleAccountResolver.class);
    when(resolver.resolve(eq(AccountRole.BANK), any())).thenReturn("1000");
    when(resolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(resolver.resolve(eq(AccountRole.QRIS_CLEARING), any())).thenReturn("1901");
    when(resolver.resolve(eq(AccountRole.CARD_CLEARING), any())).thenReturn("1902");
    when(resolver.resolve(eq(AccountRole.FIXED_ASSET_COST), any())).thenReturn("1500");
    when(resolver.resolve(eq(AccountRole.ACCUMULATED_DEPRECIATION), any())).thenReturn("1590");
    when(resolver.resolve(eq(AccountRole.GAIN_ON_DISPOSAL), any())).thenReturn("4200");
    when(resolver.resolve(eq(AccountRole.LOSS_ON_DISPOSAL), any())).thenReturn("5600");
    stubDisposals(0L, 0L, 0L, 0L, 0L);
    reader = new CashFlowReader(tb, resolver, jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void feed(GlTrialBalanceLineView... lines) {
    when(tb.read(PERIOD)).thenReturn(List.of(lines));
  }

  /**
   * Stubs the frozen disposal-aggregate query by driving the reader's own RowMapper over a mocked
   * ResultSet, so the SQL column ordering is exercised too.
   */
  @SuppressWarnings("unchecked")
  private void stubDisposals(long cost, long accumulated, long proceeds, long gain, long loss)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong(1)).thenReturn(cost);
    when(rs.getLong(2)).thenReturn(accumulated);
    when(rs.getLong(3)).thenReturn(proceeds);
    when(rs.getLong(4)).thenReturn(gain);
    when(rs.getLong(5)).thenReturn(loss);
    when(jdbc.queryForObject(any(String.class), any(RowMapper.class), eq(PERIOD)))
        .thenAnswer(inv -> ((RowMapper<Object>) inv.getArgument(1)).mapRow(rs, 0));
  }

  @Test
  void emptyPeriodIsNoStatement() {
    when(tb.read(PERIOD)).thenReturn(List.of());
    assertThat(reader.read(PERIOD)).isEmpty();
  }

  @Test
  void creditSaleIncreasesArAndUsesCashSoNetChangeIsZero() {
    // Dr AR 10,000,000 / Cr Revenue 10,000,000 (an unpaid invoice) — no cash moved.
    feed(line("1200", "ASSET", 10_000_000L, 0L), line("4000", "REVENUE", 0L, 10_000_000L));

    CashFlowResponse cf = reader.read(PERIOD).orElseThrow();
    assertThat(cf.netIncomeMinor()).isEqualTo(10_000_000L);
    // AR rose by 10,000,000 → adjustment = credit − debit = −10,000,000 (uses cash).
    assertThat(cf.operatingLines())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.accountCode()).isEqualTo("1200");
              assertThat(l.amountMinor()).isEqualTo(-10_000_000L);
            });
    assertThat(cf.cashFromOperatingMinor()).isZero();
    assertThat(cf.netChangeInCashMinor()).isZero();
    assertThat(cf.cashMovementMinor()).isZero();
    assertThat(cf.reconciled()).isTrue();
  }

  @Test
  void cashSaleAndCashExpenseMoveCashAndReconcile() {
    // Dr 1900 5,000,000 / Cr Revenue 5,000,000 ; Dr Expense 2,000,000 / Cr 1900 2,000,000.
    feed(
        line("1900", "ASSET", 5_000_000L, 2_000_000L), // cash: net +3,000,000
        line("4000", "REVENUE", 0L, 5_000_000L),
        line("5000", "EXPENSE", 2_000_000L, 0L));

    CashFlowResponse cf = reader.read(PERIOD).orElseThrow();
    assertThat(cf.netIncomeMinor()).isEqualTo(3_000_000L);
    assertThat(cf.operatingLines()).isEmpty(); // 1900 is cash, not a working-capital line
    assertThat(cf.cashFromOperatingMinor()).isEqualTo(3_000_000L);
    assertThat(cf.netChangeInCashMinor()).isEqualTo(3_000_000L);
    assertThat(cf.cashMovementMinor()).isEqualTo(3_000_000L);
    assertThat(cf.reconciled()).isTrue();
  }

  @Test
  void postingABillIncreasesApAndProvidesCash() {
    // Dr Expense 4,000,000 / Cr AP 4,000,000 (an unpaid bill) — no cash moved.
    feed(line("5000", "EXPENSE", 4_000_000L, 0L), line("2000", "LIABILITY", 0L, 4_000_000L));

    CashFlowResponse cf = reader.read(PERIOD).orElseThrow();
    assertThat(cf.netIncomeMinor()).isEqualTo(-4_000_000L);
    // AP rose by 4,000,000 → adjustment = credit − debit = +4,000,000 (provides cash).
    assertThat(cf.operatingLines())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.accountCode()).isEqualTo("2000");
              assertThat(l.amountMinor()).isEqualTo(4_000_000L);
            });
    assertThat(cf.cashFromOperatingMinor()).isZero();
    assertThat(cf.netChangeInCashMinor()).isZero();
    assertThat(cf.reconciled()).isTrue();
  }

  @Test
  void capexClassifiesAsInvestingAndDepreciationAddsBackInOperating() {
    // Capex: Dr 1500 Fixed-asset cost 12,000,000 / Cr 1900 cash 12,000,000.
    // Depreciation: Dr 5500 expense 1,000,000 / Cr 1590 accumulated 1,000,000.
    feed(
        line("1500", "ASSET", 12_000_000L, 0L), // investing (role-resolved)
        line("1900", "ASSET", 0L, 12_000_000L), // cash
        line("5500", "EXPENSE", 1_000_000L, 0L),
        line("1590", "ASSET", 0L, 1_000_000L)); // the add-back stays operating

    CashFlowResponse cf = reader.read(PERIOD).orElseThrow();
    assertThat(cf.netIncomeMinor()).isEqualTo(-1_000_000L); // the depreciation expense
    // Accumulated depreciation adds back +1,000,000 in OPERATING → operating cash effect zero.
    assertThat(cf.operatingLines())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.accountCode()).isEqualTo("1590");
              assertThat(l.amountMinor()).isEqualTo(1_000_000L);
            });
    assertThat(cf.cashFromOperatingMinor()).isZero();
    // The fixed-asset cost movement is the capex outflow under INVESTING.
    assertThat(cf.investingLines())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.accountCode()).isEqualTo("1500");
              assertThat(l.amountMinor()).isEqualTo(-12_000_000L);
            });
    assertThat(cf.cashFromInvestingMinor()).isEqualTo(-12_000_000L);
    assertThat(cf.netChangeInCashMinor()).isEqualTo(-12_000_000L);
    assertThat(cf.cashMovementMinor()).isEqualTo(-12_000_000L);
    assertThat(cf.reconciled()).isTrue();
  }

  @Test
  void aDisposalPeriodShowsProceedsUnderInvestingAndBacksTheGainOutOfOperating() throws Exception {
    // Disposal entry (ADR 0022): asset cost 12M, accumulated 1M (book 11M), sold for 12M → gain 1M.
    // Legs: Dr 1900 12M / Dr 1590 1M / Cr 1500 12M / Cr 4200 1M.
    feed(
        line("1900", "ASSET", 12_000_000L, 0L), // cash: the proceeds
        line("1590", "ASSET", 1_000_000L, 0L), // accumulated removed
        line("1500", "ASSET", 0L, 12_000_000L), // cost removed
        line("4200", "REVENUE", 0L, 1_000_000L)); // the gain (other income)
    stubDisposals(12_000_000L, 1_000_000L, 12_000_000L, 1_000_000L, 0L);

    CashFlowResponse cf = reader.read(PERIOD).orElseThrow();
    assertThat(cf.netIncomeMinor()).isEqualTo(1_000_000L); // the gain in net income
    // Operating: the 1590 disposal debit is stripped (nets to zero, line removed) and the gain is
    // backed out gross — operating cash is zero: nothing operating happened.
    assertThat(cf.operatingLines())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.accountCode()).isEqualTo("4200");
              assertThat(l.amountMinor()).isEqualTo(-1_000_000L);
            });
    assertThat(cf.cashFromOperatingMinor()).isZero();
    // Investing: the 1500 disposal credit is stripped (no capex this period → line removed) and the
    // sale shows as ONE proceeds inflow.
    assertThat(cf.investingLines())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.accountCode()).isEqualTo(CashFlowReader.DISPOSAL_PROCEEDS_CODE);
              assertThat(l.amountMinor()).isEqualTo(12_000_000L);
            });
    assertThat(cf.cashFromInvestingMinor()).isEqualTo(12_000_000L);
    assertThat(cf.netChangeInCashMinor()).isEqualTo(12_000_000L);
    assertThat(cf.cashMovementMinor()).isEqualTo(12_000_000L);
    assertThat(cf.reconciled()).isTrue();
  }

  @Test
  void aMixedCapexAndDisposalPeriodSeparatesPureCapexFromProceeds() throws Exception {
    // Same-period: acquire a new asset for 20M (Dr 1500/Cr 1900) AND scrap an old one — cost 12M,
    // accumulated 2M, proceeds 0 → loss 10M (Dr 1590 2M + Dr 5600 10M / Cr 1500 12M).
    feed(
        line("1500", "ASSET", 20_000_000L, 12_000_000L), // acquisitions + disposal credit mixed
        line("1900", "ASSET", 0L, 20_000_000L), // cash out for the capex
        line("1590", "ASSET", 2_000_000L, 0L),
        line("5600", "EXPENSE", 10_000_000L, 0L));
    stubDisposals(12_000_000L, 2_000_000L, 0L, 0L, 10_000_000L);

    CashFlowResponse cf = reader.read(PERIOD).orElseThrow();
    assertThat(cf.netIncomeMinor()).isEqualTo(-10_000_000L); // the loss
    // Operating: 1590 strip removes its line; the loss adds back +10M → operating cash zero.
    assertThat(cf.operatingLines())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.accountCode()).isEqualTo("5600");
              assertThat(l.amountMinor()).isEqualTo(10_000_000L);
            });
    assertThat(cf.cashFromOperatingMinor()).isZero();
    // Investing: raw 1500 (credit−debit = −8M) − cost 12M = −20M pure capex; NO proceeds line
    // (a zero-proceeds write-off emits none).
    assertThat(cf.investingLines())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.accountCode()).isEqualTo("1500");
              assertThat(l.amountMinor()).isEqualTo(-20_000_000L);
            });
    assertThat(cf.cashFromInvestingMinor()).isEqualTo(-20_000_000L);
    assertThat(cf.netChangeInCashMinor()).isEqualTo(-20_000_000L);
    assertThat(cf.reconciled()).isTrue();
  }

  @Test
  void anUnbalancedTrialBalanceFailsTheReconciliationAssert() {
    // Dr AR 10,000,000 / Cr Revenue 8,000,000 — Σ ≠ 0 (a bug upstream would never reach here, but
    // the reader's own assert must catch a non-reconciling set rather than emit a wrong statement).
    feed(line("1200", "ASSET", 10_000_000L, 0L), line("4000", "REVENUE", 0L, 8_000_000L));

    assertThatThrownBy(() -> reader.read(PERIOD)).isInstanceOf(IllegalStateException.class);
  }

  private static GlTrialBalanceLineView line(String code, String type, long debit, long credit) {
    return new GlTrialBalanceLineView() {
      @Override
      public String getAccountCode() {
        return code;
      }

      @Override
      public String getAccountType() {
        return type;
      }

      @Override
      public long getTotalDebitMinor() {
        return debit;
      }

      @Override
      public long getTotalCreditMinor() {
        return credit;
      }

      @Override
      public String getCurrency() {
        return "IDR";
      }

      @Override
      public boolean getUsesIllustrativeRules() {
        return false;
      }
    };
  }
}
