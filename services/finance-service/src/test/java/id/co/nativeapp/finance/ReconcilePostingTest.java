package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.finance.bank.domain.BankStatementLine;
import id.co.nativeapp.finance.bank.domain.ReconciliationCategory;
import id.co.nativeapp.finance.bank.repository.BankStatementLineRepository;
import id.co.nativeapp.finance.bank.service.ReconciliationWriter;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit tests for the ad-hoc balanced legs {@link ReconciliationWriter#buildEntry} assembles (mocked
 * {@link RoleAccountResolver}; no Spring/DB) — the bank mirror of {@link ArGlPostingTest} / {@link
 * ApGlPostingTest}. Unlike AR/AP's {@code posting_template}-driven builder, a reconciliation entry
 * is built directly (no new {@code EventKind}); this verifies the exact Dr/Cr legs for every
 * category/direction pairing, and that a wrong category-vs-direction is rejected.
 */
class ReconcilePostingTest {

  private static final Instant NOW = Instant.parse("2026-06-14T08:30:00Z");
  private static final UUID BANK_ACCOUNT = UUID.randomUUID();

  private ReconciliationWriter writer;
  private RoleAccountResolver roleResolver;

  @BeforeEach
  void setUp() {
    roleResolver = mock(RoleAccountResolver.class);
    when(roleResolver.resolve(eq(AccountRole.BANK), any())).thenReturn("1000");
    when(roleResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(roleResolver.resolve(eq(AccountRole.INTEREST_INCOME), any())).thenReturn("4100");
    when(roleResolver.resolve(eq(AccountRole.BANK_CHARGES), any())).thenReturn("5400");
    when(roleResolver.resolve(eq(AccountRole.QRIS_CLEARING), any())).thenReturn("1901");
    when(roleResolver.resolve(eq(AccountRole.QRIS_FEE_EXPENSE), any())).thenReturn("5720");

    writer =
        new ReconciliationWriter(
            mock(BankStatementLineRepository.class),
            roleResolver,
            mock(JournalEntryRepository.class),
            mock(JournalLineRepository.class),
            mock(JdbcTemplate.class),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static BankStatementLine line(long amountMinor) {
    return BankStatementLine.of(
        BANK_ACCOUNT, LocalDate.parse("2026-06-14"), amountMinor, "IDR", "desc", "ref");
  }

  @Test
  void depositClearingDebitsBankCreditsClearing() {
    JournalEntry entry =
        writer.buildEntry(
            line(1_000_000L), ReconciliationCategory.CLEARING, NOW, UUID.randomUUID());

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_000_000L);
    assertThat(lineFor(lines, "1000").getDebitMinor()).isEqualTo(1_000_000L); // Dr BANK
    assertThat(lineFor(lines, "1900").getCreditMinor()).isEqualTo(1_000_000L); // Cr CASH_CLEARING
    // Provenance-derived (was hardcoded true): every resolved role above is OFFICIAL, so the entry
    // is not badged provisional.
    assertThat(entry.isUsesIllustrativeRules()).isFalse();
  }

  @Test
  void anIllustrativeMappingBadgesTheReconciliationEntryProvisional() {
    RoleAccountResolver illustrativeResolver = mock(RoleAccountResolver.class);
    when(illustrativeResolver.resolve(eq(AccountRole.BANK), any())).thenReturn("1000");
    when(illustrativeResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(illustrativeResolver.anyIllustrative(any(), any(), any())).thenReturn(true);
    ReconciliationWriter illustrativeWriter =
        new ReconciliationWriter(
            mock(BankStatementLineRepository.class),
            illustrativeResolver,
            mock(JournalEntryRepository.class),
            mock(JournalLineRepository.class),
            mock(JdbcTemplate.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    JournalEntry entry =
        illustrativeWriter.buildEntry(
            line(1_000_000L), ReconciliationCategory.CLEARING, NOW, UUID.randomUUID());

    assertThat(entry.isUsesIllustrativeRules())
        .as("an illustrative BANK/CASH_CLEARING mapping flags the entry provisional")
        .isTrue();
  }

  @Test
  void withdrawalClearingDebitsClearingCreditsBank() {
    JournalEntry entry =
        writer.buildEntry(line(-25_000L), ReconciliationCategory.CLEARING, NOW, UUID.randomUUID());

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(25_000L);
    assertThat(lineFor(lines, "1900").getDebitMinor()).isEqualTo(25_000L); // Dr CASH_CLEARING
    assertThat(lineFor(lines, "1000").getCreditMinor()).isEqualTo(25_000L); // Cr BANK
  }

  @Test
  void withdrawalBankFeeDebitsBankChargesCreditsBank() {
    JournalEntry entry =
        writer.buildEntry(line(-25_000L), ReconciliationCategory.BANK_FEE, NOW, UUID.randomUUID());

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(25_000L);
    assertThat(lineFor(lines, "5400").getDebitMinor()).isEqualTo(25_000L); // Dr BANK_CHARGES
    assertThat(lineFor(lines, "1000").getCreditMinor()).isEqualTo(25_000L); // Cr BANK
  }

  @Test
  void depositInterestDebitsBankCreditsInterestIncome() {
    JournalEntry entry =
        writer.buildEntry(line(500_000L), ReconciliationCategory.INTEREST, NOW, UUID.randomUUID());

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(500_000L);
    assertThat(lineFor(lines, "1000").getDebitMinor()).isEqualTo(500_000L); // Dr BANK
    assertThat(lineFor(lines, "4100").getCreditMinor()).isEqualTo(500_000L); // Cr INTEREST_INCOME
  }

  @Test
  void interestOnAWithdrawalIsRejected() {
    assertThatThrownBy(
            () ->
                writer.buildEntry(
                    line(-25_000L), ReconciliationCategory.INTEREST, NOW, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bankFeeOnADepositIsRejected() {
    assertThatThrownBy(
            () ->
                writer.buildEntry(
                    line(1_000_000L), ReconciliationCategory.BANK_FEE, NOW, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // -------------------------------------------------------------------------------------------
  // ADR 0045 — QRIS_CLEARING + the optional MDR fee leg.
  // -------------------------------------------------------------------------------------------

  @Test
  void depositQrisClearingWithFeeDebitsBankAndFeeCreditsGrossToQrisClearing() {
    // QRIS_FEE_EXPENSE (5720) was seeded illustrative (V52) and superseded official by V55 (bucket A
    // of the go-live SME review) — prod no longer badges a QRIS-fee reconciliation provisional. This
    // test keeps RoleAccountResolver mocked (unit test, no DB), so it independently proves the writer
    // still DERIVES uses_illustrative_rules from whatever the resolver reports (rather than
    // hardcoding it) by forcing the illustrative branch here; see PerpetualInventoryGlConfigTest /
    // GlConfigOfficialiseTest for the real-DB provenance assertion against the live V55 data.
    when(roleResolver.anyIllustrative(any(), any(), any(), any())).thenReturn(true);
    JournalEntry entry =
        writer.buildEntry(
            line(1_000_000L),
            ReconciliationCategory.QRIS_CLEARING,
            NOW,
            UUID.randomUUID(),
            20_000L);

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(3);
    assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_020_000L);
    assertThat(lineFor(lines, "1000").getDebitMinor()).isEqualTo(1_000_000L); // Dr BANK (net)
    assertThat(lineFor(lines, "5720").getDebitMinor()).isEqualTo(20_000L); // Dr QRIS_FEE_EXPENSE
    assertThat(lineFor(lines, "1901").getCreditMinor())
        .isEqualTo(1_020_000L); // Cr QRIS_CLEARING (gross)
    assertThat(entry.isUsesIllustrativeRules())
        .as("when the fee leg's role resolves illustrative, the entry is badged provisional")
        .isTrue();
  }

  @Test
  void depositQrisClearingWithNoFeePostsOnlyTwoLegs() {
    JournalEntry entryNullFee =
        writer.buildEntry(
            line(1_000_000L), ReconciliationCategory.QRIS_CLEARING, NOW, UUID.randomUUID(), null);
    JournalEntry entryZeroFee =
        writer.buildEntry(
            line(1_000_000L), ReconciliationCategory.QRIS_CLEARING, NOW, UUID.randomUUID(), 0L);

    for (JournalEntry entry : List.of(entryNullFee, entryZeroFee)) {
      List<JournalLine> lines = entry.getLines();
      assertThat(lines).hasSize(2); // no zero-amount fee line ever written
      assertThat(totalDebit(lines)).isEqualTo(totalCredit(lines)).isEqualTo(1_000_000L);
      assertThat(lineFor(lines, "1000").getDebitMinor()).isEqualTo(1_000_000L); // Dr BANK
      assertThat(lineFor(lines, "1901").getCreditMinor())
          .isEqualTo(1_000_000L); // Cr QRIS_CLEARING (no fee -> gross == net)
    }
  }

  @Test
  void withdrawalQrisClearingIsRejected() {
    assertThatThrownBy(
            () ->
                writer.buildEntry(
                    line(-25_000L),
                    ReconciliationCategory.QRIS_CLEARING,
                    NOW,
                    UUID.randomUUID(),
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void feeMinorOnANonQrisCategoryIsRejected() {
    assertThatThrownBy(
            () ->
                writer.buildEntry(
                    line(1_000_000L),
                    ReconciliationCategory.CLEARING,
                    NOW,
                    UUID.randomUUID(),
                    10_000L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anExplicitZeroFeeOnANonQrisCategoryIsAcceptedAsNoFee() {
    // Code review W2: 0 == "no fee" semantically — a client that always sends the field must not
    // be rejected for writing the zero. Posts the ordinary 2-leg transfer.
    JournalEntry entry =
        writer.buildEntry(
            line(500_000L), ReconciliationCategory.CLEARING, NOW, UUID.randomUUID(), 0L);
    assertThat(entry.getLines()).hasSize(2);
    assertThat(totalDebit(entry.getLines()))
        .isEqualTo(totalCredit(entry.getLines()))
        .isEqualTo(500_000L);
  }

  @Test
  void negativeFeeMinorIsRejected() {
    assertThatThrownBy(
            () ->
                writer.buildEntry(
                    line(1_000_000L),
                    ReconciliationCategory.QRIS_CLEARING,
                    NOW,
                    UUID.randomUUID(),
                    -1L))
        .isInstanceOf(IllegalArgumentException.class);
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
