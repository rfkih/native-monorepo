package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.domain.JournalPostingRole;
import id.co.nativeapp.finance.gl.domain.JournalStatus;
import id.co.nativeapp.finance.gl.domain.NormalBalance;
import id.co.nativeapp.finance.gl.domain.UnbalancedJournalException;
import id.co.nativeapp.finance.mapping.domain.AccountType;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit domain tests for the double-entry GL (no Spring, no DB): the balanced invariant,
 * single-sided constraint, currency mismatch, NormalBalance derivation, and enum spot-checks. These
 * pin the rule-8 Money behaviour and the balanced invariant at the cheapest level
 * (ENGINEERING-STANDARDS §3.1 — boot the least context that proves the assertion).
 */
class GlJournalDomainTest {

  private static final Instant NOW = Instant.parse("2026-06-14T08:30:00Z");
  private static final UUID EVENT_ID = UUID.randomUUID();

  // -----------------------------------------------------------------------------------------
  // NormalBalance.of — mirrors TrialBalanceReader.applySign
  // -----------------------------------------------------------------------------------------

  @Test
  void assetAndExpenseAreDebitNormal() {
    assertThat(NormalBalance.of(AccountType.ASSET)).isEqualTo(NormalBalance.DEBIT);
    assertThat(NormalBalance.of(AccountType.EXPENSE)).isEqualTo(NormalBalance.DEBIT);
  }

  @Test
  void liabilityEquityAndRevenueAreCreditNormal() {
    assertThat(NormalBalance.of(AccountType.LIABILITY)).isEqualTo(NormalBalance.CREDIT);
    assertThat(NormalBalance.of(AccountType.EQUITY)).isEqualTo(NormalBalance.CREDIT);
    assertThat(NormalBalance.of(AccountType.REVENUE)).isEqualTo(NormalBalance.CREDIT);
  }

  @Test
  void normalBalanceOfNullThrows() {
    assertThatThrownBy(() -> NormalBalance.of(null)).isInstanceOf(NullPointerException.class);
  }

  // -----------------------------------------------------------------------------------------
  // JournalEntry.balanced — the balanced invariant factory
  // -----------------------------------------------------------------------------------------

  @Test
  void balancedFactoryProducesAValidPostedEntry() {
    UUID entryId = UUID.randomUUID();
    Money amount = Money.ofMinor(1_500_000L, "IDR");
    List<JournalLine> lines =
        List.of(
            JournalLine.debit(entryId, 1, "1900", amount),
            JournalLine.credit(entryId, 2, "4000", amount));

    JournalEntry entry =
        JournalEntry.balanced(entryId, "2026-06", NOW, "test sale", "IDR", EVENT_ID, true, lines);

    assertThat(entry.getPeriod()).isEqualTo("2026-06");
    assertThat(entry.getCurrency()).isEqualTo("IDR");
    assertThat(entry.getSourceEventId()).isEqualTo(EVENT_ID);
    assertThat(entry.isUsesIllustrativeRules()).isTrue();
    assertThat(entry.getStatus()).isEqualTo(JournalStatus.POSTED);
    assertThat(entry.getPostingRole()).isEqualTo(JournalPostingRole.PRIMARY);
    assertThat(entry.getLines()).hasSize(2);
    assertThat(entry.totalDebit()).isEqualTo(amount);
  }

  @Test
  void singleLineEntryIsRejected() {
    UUID entryId = UUID.randomUUID();
    Money amount = Money.ofMinor(1_000L, "IDR");
    List<JournalLine> lines = List.of(JournalLine.debit(entryId, 1, "1900", amount));

    assertThatThrownBy(
            () ->
                JournalEntry.balanced(
                    entryId, "2026-06", NOW, "test", "IDR", EVENT_ID, false, lines))
        .isInstanceOf(UnbalancedJournalException.class)
        .hasMessageContaining("at least 2 lines");
  }

  @Test
  void unbalancedDebitsAndCreditsAreRejected() {
    UUID entryId = UUID.randomUUID();
    List<JournalLine> lines =
        List.of(
            JournalLine.debit(entryId, 1, "1900", Money.ofMinor(1_000L, "IDR")),
            JournalLine.credit(entryId, 2, "4000", Money.ofMinor(500L, "IDR")));

    assertThatThrownBy(
            () ->
                JournalEntry.balanced(
                    entryId, "2026-06", NOW, "test", "IDR", EVENT_ID, false, lines))
        .isInstanceOf(UnbalancedJournalException.class)
        .hasMessageContaining("unbalanced");
  }

  @Test
  void mixedCurrencyLinesAreRejected() {
    UUID entryId = UUID.randomUUID();
    // debit line in USD, but credit line in IDR; entry currency is "USD"
    List<JournalLine> lines =
        List.of(
            JournalLine.debit(entryId, 1, "1900", Money.ofMinor(100L, "USD")),
            JournalLine.credit(entryId, 2, "4000", Money.ofMinor(100L, "IDR")));

    assertThatThrownBy(
            () ->
                JournalEntry.balanced(
                    entryId, "2026-06", NOW, "test", "USD", EVENT_ID, false, lines))
        .isInstanceOf(UnbalancedJournalException.class)
        .hasMessageContaining("currency");
  }

  @Test
  void zeroAmountLineIsRejectedBySingleSidedCheck() {
    UUID entryId = UUID.randomUUID();
    assertThatThrownBy(() -> JournalLine.debit(entryId, 1, "1900", Money.ofMinor(0L, "IDR")))
        .isInstanceOf(UnbalancedJournalException.class)
        .hasMessageContaining("strictly positive");
  }

  @Test
  void emptyLinesIsRejected() {
    UUID entryId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                JournalEntry.balanced(
                    entryId, "2026-06", NOW, "test", "IDR", EVENT_ID, false, List.of()))
        .isInstanceOf(UnbalancedJournalException.class);
  }

  // -----------------------------------------------------------------------------------------
  // EventKind enum
  // -----------------------------------------------------------------------------------------

  @Test
  void eventKindValuesExist() {
    assertThat(EventKind.values())
        .containsExactlyInAnyOrder(
            EventKind.SALE,
            EventKind.EXPENSE,
            EventKind.LABOR,
            EventKind.SALE_VOID,
            EventKind.SALE_REFUND);
  }
}
