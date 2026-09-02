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
import id.co.nativeapp.finance.empexpense.domain.EmployeeExpenseClaimLedger;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseClaimVoidedEvent;
import id.co.nativeapp.finance.empexpense.repository.EmployeeExpenseClaimLedgerRepository;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.GeneralLedgerWriter;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.mapping.domain.GlAccountResolution;
import id.co.nativeapp.finance.mapping.service.GlAccountResolver;
import id.co.nativeapp.finance.pnl.service.PnlReadModelWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.PostingRole;
import id.co.nativeapp.finance.revenue.domain.PostingType;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-unit proofs for {@link ExpenseClaimVoidWriter} (no Spring / no Testcontainers — mirrors
 * {@code TrialBalanceReaderTest}'s mocked-collaborator style).
 */
@ExtendWith(MockitoExtension.class)
class ExpenseClaimVoidWriterTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "finance-consumer";
  private static final UUID CLAIM_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
  private static final UUID ORG_UNIT_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
  private static final UUID EMPLOYEE_ID = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");
  private static final Instant APPROVED_AT = Instant.parse("2026-08-01T10:00:00Z");
  private static final Instant VOIDED_AT = Instant.parse("2026-08-02T09:00:00Z");

  @Mock private LedgerPostingRepository ledgerRepository;
  @Mock private ProcessedEventStore processedEvents;
  @Mock private GlAccountResolver glAccountResolver;
  @Mock private PnlReadModelWriter pnlReadModel;
  @Mock private JournalPostingService journalPostingService;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private JournalLineRepository journalLineRepository;
  @Mock private EmployeeExpenseClaimLedgerRepository claimLedgerRepository;

  private ExpenseClaimVoidWriter writer;

  @BeforeEach
  void setUp() {
    writer =
        new ExpenseClaimVoidWriter(
            ledgerRepository,
            processedEvents,
            glAccountResolver,
            pnlReadModel,
            journalPostingService,
            new GeneralLedgerWriter(journalEntryRepository, journalLineRepository),
            claimLedgerRepository);
  }

  private static ExpenseClaimVoidedEvent event(UUID eventId, long amountMinor) {
    return new ExpenseClaimVoidedEvent(
        eventId,
        CLAIM_ID,
        TENANT,
        ORG_UNIT_ID,
        EMPLOYEE_ID,
        Money.ofMinor(amountMinor, "IDR"),
        "supplies",
        APPROVED_AT,
        VOIDED_AT);
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

  private static EmployeeExpenseClaimLedger recognizedRow(long amountMinor) {
    return EmployeeExpenseClaimLedger.recognized(
        CLAIM_ID,
        EMPLOYEE_ID,
        ORG_UNIT_ID,
        Money.ofMinor(amountMinor, "IDR"),
        APPROVED_AT,
        UUID.randomUUID());
  }

  @Test
  void postsTheExactContraUnderTheSameAccountAndDimensionAndStampsTheRow() throws Exception {
    UUID eventId = UUID.randomUUID();
    ExpenseClaimVoidedEvent event = event(eventId, 250_000L);
    firstDelivery(processedEvents);
    EmployeeExpenseClaimLedger row = recognizedRow(250_000L);
    when(claimLedgerRepository.findByClaimId(CLAIM_ID)).thenReturn(Optional.of(row));
    when(glAccountResolver.resolveExpense("supplies", APPROVED_AT))
        .thenReturn(new GlAccountResolution("5200", true));

    UUID entryId = UUID.randomUUID();
    JournalEntry realEntry =
        JournalEntry.balanced(
            entryId,
            "2026-08",
            VOIDED_AT,
            "ExpenseClaimVoided",
            "IDR",
            eventId,
            false,
            List.of(
                JournalLine.debit(entryId, 1, "2600", Money.ofMinor(250_000L, "IDR")),
                JournalLine.credit(entryId, 2, "5000", Money.ofMinor(250_000L, "IDR"))));
    when(journalPostingService.buildEntry(
            eq(EventKind.EXPENSE_CLAIM_VOID),
            eq("2026-08"),
            eq(Money.ofMinor(250_000L, "IDR")),
            eq(VOIDED_AT),
            eq(eventId),
            eq("ExpenseClaimVoided"),
            eq(false)))
        .thenReturn(realEntry);

    boolean ran = TenantContext.callAs(TENANT, ACTOR, () -> writer.postVoided(event));

    assertThat(ran).isTrue();

    ArgumentCaptor<LedgerPosting> postingCaptor = ArgumentCaptor.forClass(LedgerPosting.class);
    verify(ledgerRepository).save(postingCaptor.capture());
    LedgerPosting saved = postingCaptor.getValue();
    assertThat(saved.getPostingType()).isEqualTo(PostingType.EXPENSE);
    assertThat(saved.getBusinessId()).isEqualTo(ORG_UNIT_ID);
    assertThat(saved.getGlAccountCode()).isEqualTo("5200");
    assertThat(saved.getAmount()).isEqualTo(Money.ofMinor(-250_000L, "IDR"));
    assertThat(saved.getPostingRole()).isEqualTo(PostingRole.REVERSAL);

    verify(pnlReadModel).addExpense("2026-08", Money.ofMinor(-250_000L, "IDR"), TENANT, ACTOR);
    verify(journalEntryRepository).saveAndFlush(realEntry);
    assertThat(realEntry.totalDebit()).isEqualTo(Money.ofMinor(250_000L, "IDR"));

    // The claim-ledger row was stamped with the void facts.
    verify(claimLedgerRepository).save(row);
    assertThat(row.getVoidedAt()).isEqualTo(VOIDED_AT);
    assertThat(row.getVoidEntryId()).isEqualTo(entryId);
  }

  @Test
  void anAlreadySettledClaimIsALoudSkipNoPostingIsWritten() throws Exception {
    UUID eventId = UUID.randomUUID();
    ExpenseClaimVoidedEvent event = event(eventId, 250_000L);
    firstDelivery(processedEvents);
    EmployeeExpenseClaimLedger settledRow =
        EmployeeExpenseClaimLedger.unrecognizedSettlement(
            CLAIM_ID,
            EMPLOYEE_ID,
            ORG_UNIT_ID,
            Money.ofMinor(250_000L, "IDR"),
            Instant.parse("2026-08-01T12:00:00Z"),
            "DIRECT",
            null,
            null,
            UUID.randomUUID());
    when(claimLedgerRepository.findByClaimId(CLAIM_ID)).thenReturn(Optional.of(settledRow));

    boolean ran = TenantContext.callAs(TENANT, ACTOR, () -> writer.postVoided(event));

    // The handler ran (first delivery of THIS event id) but performed no posting — a loud skip.
    assertThat(ran).isTrue();
    verifyNoInteractions(ledgerRepository, glAccountResolver, pnlReadModel, journalPostingService);
    verify(claimLedgerRepository, never()).save(any());
  }

  @Test
  void aVoidForAnUnrecognizedClaimIsALoudWarnButStillPostsTheContra() throws Exception {
    UUID eventId = UUID.randomUUID();
    ExpenseClaimVoidedEvent event = event(eventId, 250_000L);
    firstDelivery(processedEvents);
    when(claimLedgerRepository.findByClaimId(CLAIM_ID)).thenReturn(Optional.empty());
    when(glAccountResolver.resolveExpense("supplies", APPROVED_AT))
        .thenReturn(new GlAccountResolution("5200", true));

    UUID entryId = UUID.randomUUID();
    JournalEntry contraEntry =
        JournalEntry.balanced(
            entryId,
            "2026-08",
            VOIDED_AT,
            "ExpenseClaimVoided",
            "IDR",
            eventId,
            false,
            List.of(
                JournalLine.debit(entryId, 1, "2600", Money.ofMinor(250_000L, "IDR")),
                JournalLine.credit(entryId, 2, "5000", Money.ofMinor(250_000L, "IDR"))));
    when(journalPostingService.buildEntry(any(), any(), any(), any(), any(), any(), eq(false)))
        .thenReturn(contraEntry);

    boolean ran = TenantContext.callAs(TENANT, ACTOR, () -> writer.postVoided(event));

    assertThat(ran).isTrue();
    // The contra IS posted despite no claim-ledger row to reconcile against.
    verify(ledgerRepository).save(any(LedgerPosting.class));
    verify(pnlReadModel).addExpense("2026-08", Money.ofMinor(-250_000L, "IDR"), TENANT, ACTOR);
    verify(journalEntryRepository).saveAndFlush(any(JournalEntry.class));
    // Nothing to stamp — no row exists.
    verify(claimLedgerRepository, never()).save(any());
  }

  @Test
  void aRedeliveredEventIsANoOp() throws Exception {
    UUID eventId = UUID.randomUUID();
    ExpenseClaimVoidedEvent event = event(eventId, 10_000L);
    when(processedEvents.processOnce(eq(eventId), any())).thenReturn(false);

    boolean ran = TenantContext.callAs(TENANT, ACTOR, () -> writer.postVoided(event));

    assertThat(ran).isFalse();
    verifyNoInteractions(
        ledgerRepository,
        glAccountResolver,
        pnlReadModel,
        journalPostingService,
        claimLedgerRepository);
  }
}
