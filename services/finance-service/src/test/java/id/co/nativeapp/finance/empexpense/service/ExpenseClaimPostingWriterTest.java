package id.co.nativeapp.finance.empexpense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.empexpense.domain.EmployeeExpenseClaimLedger;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseClaimApprovedEvent;
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
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.pnl.service.PnlReadModelWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.PostingRole;
import id.co.nativeapp.finance.revenue.domain.PostingType;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
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
 * Pure-unit proofs for {@link ExpenseClaimPostingWriter} (no Spring / no Testcontainers — mirrors
 * {@code TrialBalanceReaderTest}'s mocked-collaborator style). Every collaborator is mocked; {@link
 * JournalPostingService#buildEntry} is stubbed to return a REAL, balanced {@link JournalEntry}
 * built via {@link JournalEntry#balanced} + {@link JournalLine#debit}/{@link JournalLine#credit} so
 * the posted legs are genuinely balanced, not merely asserted by a fake.
 */
@ExtendWith(MockitoExtension.class)
class ExpenseClaimPostingWriterTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "finance-consumer";
  private static final UUID CLAIM_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
  private static final UUID ORG_UNIT_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
  private static final UUID EMPLOYEE_ID = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");

  @Mock private LedgerPostingRepository ledgerRepository;
  @Mock private ProcessedEventStore processedEvents;
  @Mock private GlAccountResolver glAccountResolver;
  @Mock private PnlReadModelWriter pnlReadModel;
  @Mock private JournalPostingService journalPostingService;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private JournalLineRepository journalLineRepository;
  @Mock private EmployeeExpenseClaimLedgerRepository claimLedgerRepository;

  private ExpenseClaimPostingWriter writer;

  @BeforeEach
  void setUp() {
    writer =
        new ExpenseClaimPostingWriter(
            ledgerRepository,
            processedEvents,
            glAccountResolver,
            pnlReadModel,
            journalPostingService,
            new GeneralLedgerWriter(journalEntryRepository, journalLineRepository),
            claimLedgerRepository);
  }

  private static ExpenseClaimApprovedEvent event(UUID eventId, long amountMinor, String glHint) {
    return new ExpenseClaimApprovedEvent(
        eventId,
        CLAIM_ID,
        TENANT,
        ORG_UNIT_ID,
        EMPLOYEE_ID,
        Money.ofMinor(amountMinor, "IDR"),
        glHint,
        LocalDate.of(2026, 8, 1),
        Instant.parse("2026-08-02T09:00:00Z"));
  }

  /** Runs the given first-delivery {@code processOnce} claim, actually invoking the handler. */
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
  void postsABalancedEntryAndTheDimensionalPostingForAMappedHint() throws Exception {
    UUID eventId = UUID.randomUUID();
    ExpenseClaimApprovedEvent event = event(eventId, 250_000L, "supplies");
    firstDelivery(processedEvents);
    when(glAccountResolver.resolveExpense("supplies", event.approvedAt()))
        .thenReturn(new GlAccountResolution("5200", true));
    when(claimLedgerRepository.findByClaimId(CLAIM_ID)).thenReturn(Optional.empty());

    UUID entryId = UUID.randomUUID();
    JournalEntry realEntry =
        JournalEntry.balanced(
            entryId,
            "2026-08",
            event.approvedAt(),
            "ExpenseClaimApproved",
            "IDR",
            eventId,
            false,
            List.of(
                JournalLine.debit(entryId, 1, "5000", Money.ofMinor(250_000L, "IDR")),
                JournalLine.credit(entryId, 2, "2600", Money.ofMinor(250_000L, "IDR"))));
    when(journalPostingService.buildEntry(
            eq(EventKind.EXPENSE_CLAIM_APPROVED),
            eq("2026-08"),
            eq(Money.ofMinor(250_000L, "IDR")),
            eq(event.approvedAt()),
            eq(eventId),
            eq("ExpenseClaimApproved"),
            eq(false)))
        .thenReturn(realEntry);

    boolean posted = TenantContext.callAs(TENANT, ACTOR, () -> writer.postApproved(event));

    assertThat(posted).isTrue();

    ArgumentCaptor<LedgerPosting> postingCaptor = ArgumentCaptor.forClass(LedgerPosting.class);
    verify(ledgerRepository).save(postingCaptor.capture());
    LedgerPosting saved = postingCaptor.getValue();
    assertThat(saved.getPostingType()).isEqualTo(PostingType.EXPENSE);
    assertThat(saved.getBusinessId()).isEqualTo(ORG_UNIT_ID);
    assertThat(saved.getGlAccountCode()).isEqualTo("5200");
    assertThat(saved.getAmount()).isEqualTo(Money.ofMinor(250_000L, "IDR"));
    assertThat(saved.getSourceEventId()).isEqualTo(eventId);
    assertThat(saved.getPostingRole()).isEqualTo(PostingRole.PRIMARY);

    verify(pnlReadModel).addExpense("2026-08", Money.ofMinor(250_000L, "IDR"), TENANT, ACTOR);
    verify(journalEntryRepository).saveAndFlush(realEntry);
    verify(journalLineRepository, times(2)).save(any(JournalLine.class));

    // The persisted entry is genuinely balanced (built via the real JournalEntry.balanced factory).
    assertThat(realEntry.totalDebit()).isEqualTo(Money.ofMinor(250_000L, "IDR"));

    // The claim-ledger row was INSERTED with the recognition fields (no prior row existed).
    ArgumentCaptor<EmployeeExpenseClaimLedger> ledgerCaptor =
        ArgumentCaptor.forClass(EmployeeExpenseClaimLedger.class);
    verify(claimLedgerRepository).saveAndFlush(ledgerCaptor.capture());
    EmployeeExpenseClaimLedger ledgerRow = ledgerCaptor.getValue();
    assertThat(ledgerRow.getClaimId()).isEqualTo(CLAIM_ID);
    assertThat(ledgerRow.getEmployeeId()).isEqualTo(EMPLOYEE_ID);
    assertThat(ledgerRow.getOrgUnitId()).isEqualTo(ORG_UNIT_ID);
    assertThat(ledgerRow.getAmount()).isEqualTo(Money.ofMinor(250_000L, "IDR"));
    assertThat(ledgerRow.getRecognizedAt()).isEqualTo(event.approvedAt());
    assertThat(ledgerRow.getRecognitionEntryId()).isEqualTo(entryId);
    assertThat(ledgerRow.isSettled()).isFalse();
  }

  @Test
  void anUnmappableGlHintStillPostsToSuspenseMoneyIsNeverDropped() throws Exception {
    UUID eventId = UUID.randomUUID();
    ExpenseClaimApprovedEvent event = event(eventId, 99_000L, "some-unknown-category");
    firstDelivery(processedEvents);
    when(glAccountResolver.resolveExpense("some-unknown-category", event.approvedAt()))
        .thenReturn(new GlAccountResolution("9999", false));
    when(journalPostingService.buildEntry(any(), any(), any(), any(), any(), any(), eq(false)))
        .thenReturn(suspenseEntry(eventId, 99_000L));
    when(claimLedgerRepository.findByClaimId(CLAIM_ID)).thenReturn(Optional.empty());

    boolean posted = TenantContext.callAs(TENANT, ACTOR, () -> writer.postApproved(event));

    assertThat(posted).isTrue();
    ArgumentCaptor<LedgerPosting> postingCaptor = ArgumentCaptor.forClass(LedgerPosting.class);
    verify(ledgerRepository).save(postingCaptor.capture());
    assertThat(postingCaptor.getValue().getGlAccountCode()).isEqualTo("9999");
  }

  @Test
  void anApprovalArrivingAfterAnOutOfOrderSettlementReconcilesTheExistingRow() throws Exception {
    UUID eventId = UUID.randomUUID();
    ExpenseClaimApprovedEvent event = event(eventId, 250_000L, "supplies");
    firstDelivery(processedEvents);
    when(glAccountResolver.resolveExpense("supplies", event.approvedAt()))
        .thenReturn(new GlAccountResolution("5200", true));

    UUID entryId = UUID.randomUUID();
    JournalEntry realEntry =
        JournalEntry.balanced(
            entryId,
            "2026-08",
            event.approvedAt(),
            "ExpenseClaimApproved",
            "IDR",
            eventId,
            false,
            List.of(
                JournalLine.debit(entryId, 1, "5000", Money.ofMinor(250_000L, "IDR")),
                JournalLine.credit(entryId, 2, "2600", Money.ofMinor(250_000L, "IDR"))));
    when(journalPostingService.buildEntry(any(), any(), any(), any(), any(), any(), eq(false)))
        .thenReturn(realEntry);

    // A settlement already self-healed a row for this claim (unrecognized until now).
    UUID settlementEntryId = UUID.randomUUID();
    EmployeeExpenseClaimLedger existingRow =
        EmployeeExpenseClaimLedger.unrecognizedSettlement(
            CLAIM_ID,
            EMPLOYEE_ID,
            ORG_UNIT_ID,
            Money.ofMinor(250_000L, "IDR"),
            Instant.parse("2026-08-01T09:00:00Z"),
            "DIRECT",
            null,
            null,
            settlementEntryId);
    when(claimLedgerRepository.findByClaimId(CLAIM_ID)).thenReturn(Optional.of(existingRow));

    boolean posted = TenantContext.callAs(TENANT, ACTOR, () -> writer.postApproved(event));

    assertThat(posted).isTrue();

    ArgumentCaptor<EmployeeExpenseClaimLedger> ledgerCaptor =
        ArgumentCaptor.forClass(EmployeeExpenseClaimLedger.class);
    verify(claimLedgerRepository).save(ledgerCaptor.capture());
    EmployeeExpenseClaimLedger saved = ledgerCaptor.getValue();
    assertThat(saved).isSameAs(existingRow);
    assertThat(saved.isRecognized()).isTrue();
    assertThat(saved.getRecognizedAt()).isEqualTo(event.approvedAt());
    assertThat(saved.getRecognitionEntryId()).isEqualTo(entryId);
    // Already-settled facts untouched by the reconciliation.
    assertThat(saved.isSettled()).isTrue();
    assertThat(saved.getSettlementEntryId()).isEqualTo(settlementEntryId);
    verify(claimLedgerRepository, never()).saveAndFlush(any());
  }

  @Test
  void aRedeliveredEventIsANoOp() throws Exception {
    UUID eventId = UUID.randomUUID();
    ExpenseClaimApprovedEvent event = event(eventId, 10_000L, "supplies");
    when(processedEvents.processOnce(eq(eventId), any())).thenReturn(false);

    boolean posted = TenantContext.callAs(TENANT, ACTOR, () -> writer.postApproved(event));

    assertThat(posted).isFalse();
    verifyNoInteractions(ledgerRepository, glAccountResolver, pnlReadModel, journalPostingService);
  }

  @Test
  void aCurrencyDivergenceFailsClosedBeforeAnyPostingIsWritten() throws Exception {
    UUID eventId = UUID.randomUUID();
    ExpenseClaimApprovedEvent event = event(eventId, 10_000L, "supplies");
    firstDelivery(processedEvents);
    doThrow(new MismatchedPostingCurrencyException("2026-08", "USD", "IDR"))
        .when(pnlReadModel)
        .requireConsistentCurrency("2026-08", Money.ofMinor(10_000L, "IDR"));

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> writer.postApproved(event)))
        .isInstanceOf(MismatchedPostingCurrencyException.class);

    verify(ledgerRepository, never()).save(any());
    verify(journalPostingService, never())
        .buildEntry(any(), any(), any(), any(), any(), any(), eq(false));
  }

  private static JournalEntry suspenseEntry(UUID eventId, long amountMinor) {
    UUID entryId = UUID.randomUUID();
    return JournalEntry.balanced(
        entryId,
        "2026-08",
        Instant.parse("2026-08-02T09:00:00Z"),
        "ExpenseClaimApproved [SUSPENSE]",
        "IDR",
        eventId,
        true,
        List.of(
            JournalLine.debit(entryId, 1, "9999", Money.ofMinor(amountMinor, "IDR")),
            JournalLine.credit(entryId, 2, "9999", Money.ofMinor(amountMinor, "IDR"))));
  }
}
