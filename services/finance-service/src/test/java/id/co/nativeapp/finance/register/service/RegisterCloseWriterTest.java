package id.co.nativeapp.finance.register.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.register.messaging.RegisterSessionClosedEvent;
import id.co.nativeapp.finance.register.messaging.RegisterSessionClosedEvent.TenderReconciliation;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
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
  private final RegisterCloseWriter writer =
      new RegisterCloseWriter(
          mock(ProcessedEventStore.class),
          mock(JournalEntryRepository.class),
          mock(JournalLineRepository.class),
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
                new TenderReconciliation("ONLINE", 600_000L, 600_000L, 0L)));

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
  }
}
