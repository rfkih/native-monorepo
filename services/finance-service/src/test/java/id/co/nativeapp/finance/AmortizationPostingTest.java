package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.finance.assets.domain.DeferralKind;
import id.co.nativeapp.finance.assets.repository.AmortizationRunLineRepository;
import id.co.nativeapp.finance.assets.repository.AmortizationRunRepository;
import id.co.nativeapp.finance.assets.repository.DeferralRepository;
import id.co.nativeapp.finance.assets.repository.FixedAssetRepository;
import id.co.nativeapp.finance.assets.service.AmortizationRunWriter;
import id.co.nativeapp.finance.assets.service.DeferralWriter;
import id.co.nativeapp.finance.assets.service.FixedAssetWriter;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit tests for the ad-hoc balanced legs the Phase 6 writers assemble (mocked {@link
 * RoleAccountResolver}, no Spring/DB — the {@code ReconcilePostingTest} shape): the capex entry, the
 * two deferral opening entries, the monthly depreciation entry, and the two amortization entries.
 */
class AmortizationPostingTest {

  private static final Instant NOW = Instant.parse("2026-08-31T08:30:00Z");
  private static final String PERIOD = "2026-08";
  private static final Money AMOUNT = Money.ofMinor(1_000_000L, "IDR");

  private FixedAssetWriter assetWriter;
  private DeferralWriter deferralWriter;
  private AmortizationRunWriter runWriter;

  @BeforeEach
  void setUp() {
    RoleAccountResolver resolver = mock(RoleAccountResolver.class);
    when(resolver.resolve(eq(AccountRole.FIXED_ASSET_COST), any())).thenReturn("1500");
    when(resolver.resolve(eq(AccountRole.ACCUMULATED_DEPRECIATION), any())).thenReturn("1590");
    when(resolver.resolve(eq(AccountRole.DEPRECIATION_EXPENSE), any())).thenReturn("5500");
    when(resolver.resolve(eq(AccountRole.PREPAID_EXPENSE), any())).thenReturn("1400");
    when(resolver.resolve(eq(AccountRole.DEFERRED_REVENUE), any())).thenReturn("2400");
    when(resolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(resolver.resolve(eq(AccountRole.EXPENSE), any())).thenReturn("5000");
    when(resolver.resolve(eq(AccountRole.REVENUE), any())).thenReturn("4000");

    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    assetWriter =
        new FixedAssetWriter(
            mock(FixedAssetRepository.class),
            mock(JournalEntryRepository.class),
            mock(JournalLineRepository.class),
            resolver,
            mock(JdbcTemplate.class),
            clock);
    deferralWriter =
        new DeferralWriter(
            mock(DeferralRepository.class),
            mock(JournalEntryRepository.class),
            mock(JournalLineRepository.class),
            resolver,
            mock(JdbcTemplate.class),
            clock);
    runWriter =
        new AmortizationRunWriter(
            mock(FixedAssetRepository.class),
            mock(DeferralRepository.class),
            mock(AmortizationRunRepository.class),
            mock(AmortizationRunLineRepository.class),
            mock(JournalEntryRepository.class),
            mock(JournalLineRepository.class),
            resolver,
            mock(JdbcTemplate.class),
            clock);
  }

  @Test
  void acquisitionDebitsAssetCostCreditsClearing() {
    JournalEntry entry =
        assetWriter.buildAcquisitionEntry(
            PERIOD, NOW, UUID.randomUUID(), UUID.randomUUID(), Money.ofMinor(12_000_000L, "IDR"));

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(12_000_000L);
    assertThat(lineFor(lines, "1500").getDebitMinor()).isEqualTo(12_000_000L); // Dr FA cost
    assertThat(lineFor(lines, "1900").getCreditMinor()).isEqualTo(12_000_000L); // Cr clearing
  }

  @Test
  void prepaidCreationDebitsPrepaidCreditsClearing() {
    JournalEntry entry =
        deferralWriter.buildCreationEntry(
            DeferralKind.PREPAID_EXPENSE, PERIOD, NOW, UUID.randomUUID(), UUID.randomUUID(), AMOUNT);

    List<JournalLine> lines = entry.getLines();
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_000_000L);
    assertThat(lineFor(lines, "1400").getDebitMinor()).isEqualTo(1_000_000L); // Dr prepaid
    assertThat(lineFor(lines, "1900").getCreditMinor()).isEqualTo(1_000_000L); // Cr clearing
  }

  @Test
  void deferredRevenueCreationDebitsClearingCreditsDeferred() {
    JournalEntry entry =
        deferralWriter.buildCreationEntry(
            DeferralKind.DEFERRED_REVENUE, PERIOD, NOW, UUID.randomUUID(), UUID.randomUUID(), AMOUNT);

    List<JournalLine> lines = entry.getLines();
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_000_000L);
    assertThat(lineFor(lines, "1900").getDebitMinor()).isEqualTo(1_000_000L); // Dr clearing (cash in)
    assertThat(lineFor(lines, "2400").getCreditMinor()).isEqualTo(1_000_000L); // Cr deferred revenue
  }

  @Test
  void depreciationDebitsExpenseCreditsAccumulated() {
    JournalEntry entry =
        runWriter.buildAssetEntry(PERIOD, NOW, UUID.randomUUID(), UUID.randomUUID(), AMOUNT);

    List<JournalLine> lines = entry.getLines();
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_000_000L);
    assertThat(lineFor(lines, "5500").getDebitMinor()).isEqualTo(1_000_000L); // Dr dep expense
    assertThat(lineFor(lines, "1590").getCreditMinor()).isEqualTo(1_000_000L); // Cr accum dep
  }

  @Test
  void prepaidAmortizationDebitsExpenseCreditsPrepaid() {
    JournalEntry entry =
        runWriter.buildDeferralEntry(
            DeferralKind.PREPAID_EXPENSE, PERIOD, NOW, UUID.randomUUID(), UUID.randomUUID(), AMOUNT);

    List<JournalLine> lines = entry.getLines();
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_000_000L);
    assertThat(lineFor(lines, "5000").getDebitMinor()).isEqualTo(1_000_000L); // Dr expense
    assertThat(lineFor(lines, "1400").getCreditMinor()).isEqualTo(1_000_000L); // Cr prepaid
  }

  @Test
  void deferredRevenueRecognitionDebitsDeferredCreditsRevenue() {
    JournalEntry entry =
        runWriter.buildDeferralEntry(
            DeferralKind.DEFERRED_REVENUE, PERIOD, NOW, UUID.randomUUID(), UUID.randomUUID(), AMOUNT);

    List<JournalLine> lines = entry.getLines();
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_000_000L);
    assertThat(lineFor(lines, "2400").getDebitMinor()).isEqualTo(1_000_000L); // Dr deferred revenue
    assertThat(lineFor(lines, "4000").getCreditMinor()).isEqualTo(1_000_000L); // Cr revenue
  }

  private static long totalDebit(List<JournalLine> lines) {
    return lines.stream().mapToLong(JournalLine::getDebitMinor).sum();
  }

  private static long totalCredit(List<JournalLine> lines) {
    return lines.stream().mapToLong(JournalLine::getCreditMinor).sum();
  }

  private static JournalLine lineFor(List<JournalLine> lines, String accountCode) {
    return lines.stream()
        .filter(l -> l.getAccountCode().equals(accountCode))
        .findFirst()
        .orElseThrow();
  }
}
