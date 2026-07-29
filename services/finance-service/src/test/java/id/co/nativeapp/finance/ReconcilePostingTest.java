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

  @BeforeEach
  void setUp() {
    RoleAccountResolver roleResolver = mock(RoleAccountResolver.class);
    when(roleResolver.resolve(eq(AccountRole.BANK), any())).thenReturn("1000");
    when(roleResolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(roleResolver.resolve(eq(AccountRole.INTEREST_INCOME), any())).thenReturn("4100");
    when(roleResolver.resolve(eq(AccountRole.BANK_CHARGES), any())).thenReturn("5400");

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
