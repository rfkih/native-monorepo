package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.GeneralLedgerWriter;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.tax.domain.NetDirection;
import id.co.nativeapp.finance.tax.domain.NoVatActivityException;
import id.co.nativeapp.finance.tax.domain.TaxFiling;
import id.co.nativeapp.finance.tax.domain.TaxFilingStateException;
import id.co.nativeapp.finance.tax.domain.TaxFilingStatus;
import id.co.nativeapp.finance.tax.repository.TaxFilingRepository;
import id.co.nativeapp.finance.tax.service.TaxFilingWriter;
import id.co.nativeapp.finance.tax.service.TaxSettlementWriter;
import id.co.nativeapp.finance.tax.service.VatReturnReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit tests for the ad-hoc balanced legs the tax writers assemble — {@link
 * TaxFilingWriter#buildFilingEntry} (the period-end netting entry, for every payable/creditable/nil
 * case) and {@link TaxSettlementWriter#buildSettlementEntry} — with a mocked {@link
 * RoleAccountResolver} and no Spring/DB, plus the {@link TaxFiling} state machine. The tax mirror
 * of {@link ReconcilePostingTest}: filing posts a directly-built entry (no {@code
 * posting_template}), so this pins the exact Dr/Cr legs and the guards.
 */
class TaxFilingPostingTest {

  private static final Instant NOW = Instant.parse("2026-07-31T08:30:00Z");
  private static final String PERIOD = "2026-07";
  private static final String IDR = "IDR";

  private TaxFilingWriter filingWriter;
  private TaxSettlementWriter settlementWriter;

  @BeforeEach
  void setUp() {
    RoleAccountResolver roleResolver = mock(RoleAccountResolver.class);
    when(roleResolver.resolve(eq(AccountRole.VAT_OUTPUT), any())).thenReturn("2200");
    when(roleResolver.resolve(eq(AccountRole.VAT_INPUT), any())).thenReturn("1300");
    when(roleResolver.resolve(eq(AccountRole.VAT_PAYABLE), any())).thenReturn("2300");
    when(roleResolver.resolve(eq(AccountRole.VAT_CREDIT_CARRYFORWARD), any())).thenReturn("1310");
    when(roleResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");

    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    filingWriter =
        new TaxFilingWriter(
            mock(VatReturnReader.class),
            roleResolver,
            mock(TaxFilingRepository.class),
            new GeneralLedgerWriter(
                mock(JournalEntryRepository.class), mock(JournalLineRepository.class)),
            mock(JdbcTemplate.class),
            clock);
    settlementWriter =
        new TaxSettlementWriter(
            mock(TaxFilingRepository.class),
            roleResolver,
            new GeneralLedgerWriter(
                mock(JournalEntryRepository.class), mock(JournalLineRepository.class)),
            mock(JdbcTemplate.class),
            clock);
  }

  private JournalEntry filing(long output, long input) {
    return filingWriter.buildFilingEntry(
        PERIOD, NOW, UUID.randomUUID(), UUID.randomUUID(), IDR, output, input);
  }

  // ----- filing netting entry -------------------------------------------------------------------

  @Test
  void payableNetsOutputAgainstInputAndCreditsVatPayable() {
    JournalEntry entry = filing(1_100_000L, 440_000L); // net = 660,000 PAYABLE

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(3);
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_100_000L);
    assertThat(lineFor(lines, "2200").getDebitMinor()).isEqualTo(1_100_000L); // Dr VAT_OUTPUT
    assertThat(lineFor(lines, "1300").getCreditMinor()).isEqualTo(440_000L); // Cr VAT_INPUT
    assertThat(lineFor(lines, "2300").getCreditMinor()).isEqualTo(660_000L); // Cr VAT_PAYABLE (net)
    // Provenance-derived (was hardcoded true): every resolved role above is OFFICIAL, so the entry
    // is not badged provisional.
    assertThat(entry.isUsesIllustrativeRules()).isFalse();
  }

  @Test
  void anIllustrativeMappingBadgesTheFilingEntryProvisional() {
    RoleAccountResolver illustrativeResolver = mock(RoleAccountResolver.class);
    when(illustrativeResolver.resolve(eq(AccountRole.VAT_OUTPUT), any())).thenReturn("2200");
    when(illustrativeResolver.resolve(eq(AccountRole.VAT_INPUT), any())).thenReturn("1300");
    when(illustrativeResolver.resolve(eq(AccountRole.VAT_PAYABLE), any())).thenReturn("2300");
    when(illustrativeResolver.anyIllustrative(any(), any(), any(), any())).thenReturn(true);
    TaxFilingWriter illustrativeWriter =
        new TaxFilingWriter(
            mock(VatReturnReader.class),
            illustrativeResolver,
            mock(TaxFilingRepository.class),
            new GeneralLedgerWriter(
                mock(JournalEntryRepository.class), mock(JournalLineRepository.class)),
            mock(JdbcTemplate.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    JournalEntry entry =
        illustrativeWriter.buildFilingEntry(
            PERIOD, NOW, UUID.randomUUID(), UUID.randomUUID(), IDR, 1_100_000L, 440_000L);

    assertThat(entry.isUsesIllustrativeRules())
        .as("an illustrative mapping among the roles posted flags the entry provisional")
        .isTrue();
  }

  @Test
  void creditableNetsOutputAgainstInputAndDebitsCarryforward() {
    JournalEntry entry = filing(200_000L, 550_000L); // net = -350,000 CREDITABLE

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(3);
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(550_000L);
    assertThat(lineFor(lines, "2200").getDebitMinor()).isEqualTo(200_000L); // Dr VAT_OUTPUT
    assertThat(lineFor(lines, "1300").getCreditMinor()).isEqualTo(550_000L); // Cr VAT_INPUT
    assertThat(lineFor(lines, "1310").getDebitMinor()).isEqualTo(350_000L); // Dr carryforward (net)
  }

  @Test
  void zeroNetPostsOnlyTheTwoClearingLegs() {
    JournalEntry entry = filing(300_000L, 300_000L); // net = 0

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(300_000L);
    assertThat(lineFor(lines, "2200").getDebitMinor()).isEqualTo(300_000L);
    assertThat(lineFor(lines, "1300").getCreditMinor()).isEqualTo(300_000L);
    assertThat(entry.isUsesIllustrativeRules())
        .isFalse(); // provenance-derived (was hardcoded true)
  }

  @Test
  void outputOnlyOmitsTheInputLeg() {
    JournalEntry entry = filing(500_000L, 0L); // all payable

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(lineFor(lines, "2200").getDebitMinor()).isEqualTo(500_000L); // Dr VAT_OUTPUT
    assertThat(lineFor(lines, "2300").getCreditMinor()).isEqualTo(500_000L); // Cr VAT_PAYABLE
  }

  @Test
  void inputOnlyOmitsTheOutputLeg() {
    JournalEntry entry = filing(0L, 400_000L); // all creditable

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(lineFor(lines, "1300").getCreditMinor()).isEqualTo(400_000L); // Cr VAT_INPUT
    assertThat(lineFor(lines, "1310").getDebitMinor()).isEqualTo(400_000L); // Dr carryforward
  }

  @Test
  void nilPeriodIsRejected() {
    assertThatThrownBy(() -> filing(0L, 0L)).isInstanceOf(NoVatActivityException.class);
  }

  @Test
  void negativeVatPeriodIsRejected() {
    assertThatThrownBy(() -> filing(-100L, 0L)).isInstanceOf(NoVatActivityException.class);
    assertThatThrownBy(() -> filing(0L, -100L)).isInstanceOf(NoVatActivityException.class);
  }

  // ----- settlement entry -----------------------------------------------------------------------

  @Test
  void settlementDebitsVatPayableCreditsCashClearing() {
    TaxFiling payable = payableFiling(660_000L);
    JournalEntry entry =
        settlementWriter.buildSettlementEntry(payable, PERIOD, NOW, UUID.randomUUID());

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(660_000L);
    assertThat(lineFor(lines, "2300").getDebitMinor()).isEqualTo(660_000L); // Dr VAT_PAYABLE
    assertThat(lineFor(lines, "1900").getCreditMinor()).isEqualTo(660_000L); // Cr CASH_CLEARING
    assertThat(entry.isUsesIllustrativeRules())
        .isFalse(); // provenance-derived (was hardcoded true)
  }

  @Test
  void anIllustrativeMappingBadgesTheSettlementEntryProvisional() {
    RoleAccountResolver illustrativeResolver = mock(RoleAccountResolver.class);
    when(illustrativeResolver.resolve(eq(AccountRole.VAT_PAYABLE), any())).thenReturn("2300");
    when(illustrativeResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(illustrativeResolver.anyIllustrative(any(), any(), any())).thenReturn(true);
    TaxSettlementWriter illustrativeWriter =
        new TaxSettlementWriter(
            mock(TaxFilingRepository.class),
            illustrativeResolver,
            new GeneralLedgerWriter(
                mock(JournalEntryRepository.class), mock(JournalLineRepository.class)),
            mock(JdbcTemplate.class),
            Clock.fixed(NOW, ZoneOffset.UTC));
    TaxFiling payable = payableFiling(660_000L);

    JournalEntry entry =
        illustrativeWriter.buildSettlementEntry(payable, PERIOD, NOW, UUID.randomUUID());

    assertThat(entry.isUsesIllustrativeRules())
        .as("an illustrative VAT_PAYABLE/CASH_CLEARING mapping flags the entry provisional")
        .isTrue();
  }

  // ----- TaxFiling state machine ----------------------------------------------------------------

  @Test
  void payableFilingSettlesOnce() {
    TaxFiling filing = payableFiling(660_000L);
    assertThat(filing.getStatus()).isEqualTo(TaxFilingStatus.FILED);
    assertThat(filing.getNetDirection()).isEqualTo(NetDirection.PAYABLE);
    assertThat(filing.isSettleable()).isTrue();

    UUID settlementEntryId = UUID.randomUUID();
    filing.settle(settlementEntryId, NOW);
    assertThat(filing.getStatus()).isEqualTo(TaxFilingStatus.SETTLED);
    assertThat(filing.getSettlementEntryId()).isEqualTo(settlementEntryId);

    assertThatThrownBy(() -> filing.settle(UUID.randomUUID(), NOW))
        .isInstanceOf(TaxFilingStateException.class);
  }

  @Test
  void creditableFilingCannotBeSettled() {
    // output 200,000 - input 550,000 = 350,000 CREDITABLE
    TaxFiling creditable =
        TaxFiling.file(UUID.randomUUID(), PERIOD, IDR, 200_000L, 550_000L, UUID.randomUUID(), NOW);
    assertThat(creditable.getNetDirection()).isEqualTo(NetDirection.CREDITABLE);
    assertThat(creditable.isSettleable()).isFalse();
    assertThatThrownBy(() -> creditable.settle(UUID.randomUUID(), NOW))
        .isInstanceOf(TaxFilingStateException.class);
  }

  @Test
  void zeroNetPayableFilingHasNothingToSettle() {
    TaxFiling zero = payableFiling(0L); // output == input
    assertThat(zero.getNetDirection()).isEqualTo(NetDirection.PAYABLE);
    assertThat(zero.getNetMinor()).isZero();
    assertThat(zero.isSettleable()).isFalse();
    assertThatThrownBy(() -> zero.settle(UUID.randomUUID(), NOW))
        .isInstanceOf(TaxFilingStateException.class);
  }

  private static TaxFiling payableFiling(long net) {
    // output = 500,000 + net, input = 500,000 → net payable of `net`.
    return TaxFiling.file(
        UUID.randomUUID(), PERIOD, IDR, 500_000L + net, 500_000L, UUID.randomUUID(), NOW);
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
