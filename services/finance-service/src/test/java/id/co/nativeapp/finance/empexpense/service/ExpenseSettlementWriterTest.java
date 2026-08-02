package id.co.nativeapp.finance.empexpense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.empexpense.domain.EmployeeExpenseSettlement;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseReimbursementSettledEvent;
import id.co.nativeapp.finance.empexpense.repository.EmployeeExpenseSettlementRepository;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-unit proofs for {@link ExpenseSettlementWriter} (no Spring / no Testcontainers — mirrors
 * {@code TrialBalanceReaderTest}'s mocked-collaborator style). Locks the settle-once invariant (ADR
 * 0030 §7): a claim already carrying a guard row is a logged no-op, never a second posting.
 */
@ExtendWith(MockitoExtension.class)
class ExpenseSettlementWriterTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "finance-consumer";
  private static final UUID CLAIM_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
  private static final UUID ORG_UNIT_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
  private static final UUID EMPLOYEE_ID = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");
  private static final Instant SETTLED_AT = Instant.parse("2026-08-03T09:00:00Z");

  @Mock private ProcessedEventStore processedEvents;
  @Mock private EmployeeExpenseSettlementRepository settlementRepository;
  @Mock private JournalPostingService journalPostingService;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private JournalLineRepository journalLineRepository;

  private ExpenseSettlementWriter writer;

  @BeforeEach
  void setUp() {
    writer =
        new ExpenseSettlementWriter(
            processedEvents,
            settlementRepository,
            journalPostingService,
            journalEntryRepository,
            journalLineRepository);
  }

  private static ExpenseReimbursementSettledEvent directEvent(UUID eventId, long amountMinor) {
    return new ExpenseReimbursementSettledEvent(
        eventId,
        CLAIM_ID,
        TENANT,
        ORG_UNIT_ID,
        EMPLOYEE_ID,
        Money.ofMinor(amountMinor, "IDR"),
        "DIRECT",
        null,
        null,
        SETTLED_AT);
  }

  private static void firstDelivery(ProcessedEventStore processedEvents) {
    doAnswer(
            invocation -> {
              invocation.getArgument(1, Runnable.class).run();
              return true;
            })
        .when(processedEvents)
        .processOnce(any(), any());
  }

  @Test
  void settlesOnceInsertingTheGuardRowAndTheJournalEntry() throws Exception {
    UUID eventId = UUID.randomUUID();
    ExpenseReimbursementSettledEvent event = directEvent(eventId, 250_000L);
    firstDelivery(processedEvents);
    when(settlementRepository.existsByClaimId(CLAIM_ID)).thenReturn(false);

    UUID entryId = UUID.randomUUID();
    JournalEntry realEntry =
        JournalEntry.balanced(
            entryId,
            "2026-08",
            SETTLED_AT,
            "ExpenseReimbursementSettled",
            "IDR",
            eventId,
            false,
            List.of(
                JournalLine.debit(entryId, 1, "2600", Money.ofMinor(250_000L, "IDR")),
                JournalLine.credit(entryId, 2, "1900", Money.ofMinor(250_000L, "IDR"))));
    when(journalPostingService.buildEntry(
            eq(EventKind.EXPENSE_CLAIM_SETTLED),
            eq("2026-08"),
            eq(Money.ofMinor(250_000L, "IDR")),
            eq(SETTLED_AT),
            eq(eventId),
            eq("ExpenseReimbursementSettled"),
            eq(false)))
        .thenReturn(realEntry);

    boolean ran = TenantContext.callAs(TENANT, ACTOR, () -> writer.settle(event));

    assertThat(ran).isTrue();

    ArgumentCaptor<EmployeeExpenseSettlement> guardCaptor =
        ArgumentCaptor.forClass(EmployeeExpenseSettlement.class);
    verify(settlementRepository).saveAndFlush(guardCaptor.capture());
    EmployeeExpenseSettlement guard = guardCaptor.getValue();
    assertThat(guard.getClaimId()).isEqualTo(CLAIM_ID);
    assertThat(guard.getSettlementKind()).isEqualTo("DIRECT");
    assertThat(guard.getPayrollRunId()).isNull();
    assertThat(guard.getRunSeq()).isNull();
    assertThat(guard.getJournalEntryId()).isEqualTo(entryId);
    assertThat(guard.getSettledAt()).isEqualTo(SETTLED_AT);

    verify(journalEntryRepository).saveAndFlush(realEntry);
    assertThat(realEntry.totalDebit()).isEqualTo(Money.ofMinor(250_000L, "IDR"));
  }

  @Test
  void aClaimAlreadyCarryingTheGuardRowIsALoggedNoOpAcrossDistinctEventIds() throws Exception {
    // Simulates the payroll-supersession re-emission: a DIFFERENT event id for the SAME claim,
    // arriving after another event id already settled it.
    UUID secondEventId = UUID.randomUUID();
    ExpenseReimbursementSettledEvent event = directEvent(secondEventId, 250_000L);
    firstDelivery(processedEvents);
    when(settlementRepository.existsByClaimId(CLAIM_ID)).thenReturn(true);

    boolean ran = TenantContext.callAs(TENANT, ACTOR, () -> writer.settle(event));

    // The handler ran for this (new) event id, but performed no posting — a no-op.
    assertThat(ran).isTrue();
    verify(settlementRepository, never()).saveAndFlush(any());
    verifyNoInteractions(journalPostingService, journalEntryRepository, journalLineRepository);
  }

  @Test
  void aRedeliveredEventIdIsANoOp() throws Exception {
    UUID eventId = UUID.randomUUID();
    ExpenseReimbursementSettledEvent event = directEvent(eventId, 10_000L);
    when(processedEvents.processOnce(eq(eventId), any())).thenReturn(false);

    boolean ran = TenantContext.callAs(TENANT, ACTOR, () -> writer.settle(event));

    assertThat(ran).isFalse();
    verifyNoInteractions(settlementRepository, journalPostingService, journalEntryRepository);
  }

  @Test
  void existsByClaimIdForReplayDelegatesToTheRepository() {
    when(settlementRepository.existsByClaimId(CLAIM_ID)).thenReturn(true);

    assertThat(writer.existsByClaimIdForReplay(CLAIM_ID)).isTrue();
  }
}
