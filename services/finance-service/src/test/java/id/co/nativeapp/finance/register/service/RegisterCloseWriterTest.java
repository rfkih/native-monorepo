package id.co.nativeapp.finance.register.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.projection.JournalLineReversalView;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.GeneralLedgerWriter;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.register.messaging.RegisterSessionClosedEvent;
import id.co.nativeapp.finance.register.messaging.RegisterSessionClosedEvent.TenderReconciliation;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit pins for {@link RegisterCloseWriter#buildEntry} multi-tender posting (ADR 0038 phase 2): one
 * balanced debit/credit pair per tender with a non-zero variance, each truing its OWN clearing
 * account, with cash and every non-cash tender sharing the SHORT/OVER accounts. The resolver is
 * mocked and {@code buildEntry} is pure, so the exact legs are asserted directly.
 */
class RegisterCloseWriterTest {

  private final RoleAccountResolver resolver = mock(RoleAccountResolver.class);

  // RegisterCloseWriter keeps both journal repositories for its READ path (prior-entry lookup on
  // an ADR 0064 close correction) and additionally takes the GeneralLedgerWriter for the WRITE
  // path (ADR 0071). The writer wraps these same mocks, so both paths observe one set of stubs.
  private final JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
  private final JournalLineRepository journalLineRepository = mock(JournalLineRepository.class);

  private final RegisterCloseWriter writer =
      new RegisterCloseWriter(
          mock(ProcessedEventStore.class),
          journalEntryRepository,
          new GeneralLedgerWriter(
              journalEntryRepository,
              journalLineRepository,
              mock(OutboxWriter.class),
              Clock.systemUTC()),
          journalLineRepository,
          resolver,
          mock(LedgerPostingRepository.class),
          mock(ErrorInboxWriter.class),
          mock(JdbcTemplate.class));

  @Test
  void buildsOnePairPerTenderTruingEachClearingAccount() {
    when(resolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(resolver.resolve(eq(AccountRole.CARD_CLEARING), any())).thenReturn("1902");
    when(resolver.resolve(eq(AccountRole.CASH_SHORT_EXPENSE), any())).thenReturn("5700");
    when(resolver.resolve(eq(AccountRole.CASH_OVER_INCOME), any())).thenReturn("4300");

    // Cash SHORT 50k + CARD OVER 10k (ONLINE balanced → no line).
    RegisterSessionClosedEvent event =
        new RegisterSessionClosedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "11111111-1111-1111-1111-111111111111",
            UUID.randomUUID(),
            Instant.ofEpochMilli(1_750_000_000_000L),
            Instant.ofEpochMilli(1_750_030_000_000L),
            50_000L,
            0L,
            0L,
            50_000L,
            0L,
            -50_000L,
            "IDR",
            List.of(
                new TenderReconciliation("CARD", 800_000L, 810_000L, 10_000L),
                new TenderReconciliation("ONLINE", 600_000L, 600_000L, 0L)),
            null,
            1,
            null);

    JournalEntry entry = writer.buildEntry(event, UUID.randomUUID(), "2026-08");

    List<JournalLine> lines = entry.getLines();
    // Only the two non-zero variances post — ONLINE balanced contributes nothing.
    assertThat(lines).hasSize(4);
    // CASH short 50k → Dr CASH_SHORT_EXPENSE 5700 / Cr CASH_CLEARING 1900.
    assertThat(lines.get(0).getAccountCode()).isEqualTo("5700");
    assertThat(lines.get(0).getDebitMinor()).isEqualTo(50_000L);
    assertThat(lines.get(1).getAccountCode()).isEqualTo("1900");
    assertThat(lines.get(1).getCreditMinor()).isEqualTo(50_000L);
    // CARD over 10k → Dr CARD_CLEARING 1902 / Cr CASH_OVER_INCOME 4300.
    assertThat(lines.get(2).getAccountCode()).isEqualTo("1902");
    assertThat(lines.get(2).getDebitMinor()).isEqualTo(10_000L);
    assertThat(lines.get(3).getAccountCode()).isEqualTo("4300");
    assertThat(lines.get(3).getCreditMinor()).isEqualTo(10_000L);
    // Provenance-derived (was hardcoded true): every resolved role above is OFFICIAL, so the entry
    // is not badged provisional.
    assertThat(entry.isUsesIllustrativeRules()).isFalse();
  }

  @Test
  void anIllustrativeMappingBadgesTheVarianceEntryProvisional() {
    when(resolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(resolver.resolve(eq(AccountRole.CASH_SHORT_EXPENSE), any())).thenReturn("5700");
    // 1 tender (CASH) → 2 roles (its clearing role + CASH_SHORT_EXPENSE) → Instant + 2 varargs.
    when(resolver.anyIllustrative(any(), any(), any())).thenReturn(true);

    RegisterSessionClosedEvent event =
        new RegisterSessionClosedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "11111111-1111-1111-1111-111111111111",
            UUID.randomUUID(),
            Instant.ofEpochMilli(1_750_000_000_000L),
            Instant.ofEpochMilli(1_750_030_000_000L),
            50_000L,
            0L,
            0L,
            50_000L,
            0L,
            -50_000L,
            "IDR",
            List.of(),
            null,
            1,
            null);

    JournalEntry entry = writer.buildEntry(event, UUID.randomUUID(), "2026-08");

    assertThat(entry.isUsesIllustrativeRules())
        .as("an illustrative CASH_CLEARING/CASH_SHORT_EXPENSE mapping flags the entry provisional")
        .isTrue();
  }

  @Test
  void reversalContraNegatesEveryLegOfThePriorVariance() {
    // Prior entry = a cash SHORT of 50k: Dr CASH_SHORT_EXPENSE 5700 / Cr CASH_CLEARING 1900.
    // The correction's contra must NEGATE it: Cr 5700 / Dr 1900 (same accounts, sides swapped), so
    // prior + contra net to zero and the corrected variance below stands alone (ADR 0064).
    List<JournalLineReversalView> priorLines =
        List.of(
            new StubLine("5700", 50_000L, 0L, "IDR", 1),
            new StubLine("1900", 0L, 50_000L, "IDR", 2));

    JournalEntry contra =
        writer.buildReversalEntry(
            priorLines,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "2026-08",
            Instant.ofEpochMilli(1_750_030_000_000L),
            "IDR",
            false); // the caller derives this from the corrected variance's own roles

    List<JournalLine> lines = contra.getLines();
    assertThat(lines).hasSize(2);
    // Original Dr 5700 50k → contra Cr 5700 50k.
    assertThat(lines.get(0).getAccountCode()).isEqualTo("5700");
    assertThat(lines.get(0).getCreditMinor()).isEqualTo(50_000L);
    assertThat(lines.get(0).getDebitMinor()).isZero();
    // Original Cr 1900 50k → contra Dr 1900 50k.
    assertThat(lines.get(1).getAccountCode()).isEqualTo("1900");
    assertThat(lines.get(1).getDebitMinor()).isEqualTo(50_000L);
    assertThat(lines.get(1).getCreditMinor()).isZero();
    // Balanced (JournalEntry.balanced would have thrown otherwise): Σdebit == Σcredit.
    assertThat(lines.stream().mapToLong(JournalLine::getDebitMinor).sum())
        .isEqualTo(lines.stream().mapToLong(JournalLine::getCreditMinor).sum());
    // The caller-supplied flag passes through verbatim (buildReversalEntry does not derive it).
    assertThat(contra.isUsesIllustrativeRules()).isFalse();
  }

  @Test
  void reversalEntryCarriesWhicheverFlagTheCallerDerived() {
    // buildReversalEntry has no resolver lookups of its own — it just carries the caller's derived
    // flag verbatim, so the reversal always matches the corrected variance entry it is paired with.
    List<JournalLineReversalView> priorLines =
        List.of(
            new StubLine("5700", 50_000L, 0L, "IDR", 1),
            new StubLine("1900", 0L, 50_000L, "IDR", 2));

    JournalEntry contra =
        writer.buildReversalEntry(
            priorLines,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "2026-08",
            Instant.ofEpochMilli(1_750_030_000_000L),
            "IDR",
            true);

    assertThat(contra.isUsesIllustrativeRules()).isTrue();
  }

  /** Minimal {@link JournalLineReversalView} stub for the pure reversal-builder test. */
  private record StubLine(
      String accountCode, long debitMinor, long creditMinor, String currency, int lineNo)
      implements JournalLineReversalView {
    @Override
    public UUID getId() {
      return UUID.randomUUID();
    }

    @Override
    public String getAccountCode() {
      return accountCode;
    }

    @Override
    public long getDebitMinor() {
      return debitMinor;
    }

    @Override
    public long getCreditMinor() {
      return creditMinor;
    }

    @Override
    public String getCurrency() {
      return currency;
    }

    @Override
    public int getLineNo() {
      return lineNo;
    }
  }
}
