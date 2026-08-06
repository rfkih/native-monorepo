package id.co.nativeapp.finance.stocktake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.pnl.service.PnlReadModelWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.PostingType;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.finance.stocktake.messaging.StocktakeCompletedEvent;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit pins for {@link StocktakeWriter} (ADR 0038 phase 3). Two layers:
 *
 * <ul>
 *   <li>{@link StocktakeWriter#buildEntry} (pure): a LOSS debits INVENTORY_SHRINKAGE / credits
 *       INVENTORY, a GAIN debits INVENTORY / credits INVENTORY_SHRINKAGE, both for the absolute
 *       magnitude; zero / {@code Long.MIN_VALUE} are rejected.
 *   <li>{@link StocktakeWriter#post} (mocked collaborators): the W1 fix — a non-zero shrinkage also
 *       lands on BOTH P&amp;L read models (a dimensional {@code LedgerPosting(EXPENSE, …, 5800)}
 *       and {@code PnlReadModelWriter.addExpense}) with the SIGNED amount (positive loss / negative
 *       overage), so the dashboards owners read reflect it — while zero and sealed-period write
 *       nothing.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class StocktakeWriterTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "finance-consumer";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Mock private ProcessedEventStore processedEvents;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private JournalLineRepository journalLineRepository;
  @Mock private RoleAccountResolver resolver;
  @Mock private LedgerPostingRepository ledgerRepository;
  @Mock private PnlReadModelWriter pnlReadModel;
  @Mock private ErrorInboxWriter errorInbox;
  @Mock private JdbcTemplate jdbcTemplate;

  private StocktakeWriter writer() {
    return new StocktakeWriter(
        processedEvents,
        journalEntryRepository,
        journalLineRepository,
        resolver,
        ledgerRepository,
        pnlReadModel,
        errorInbox,
        jdbcTemplate);
  }

  private static StocktakeCompletedEvent event(long shrinkageMinor) {
    return new StocktakeCompletedEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        TENANT,
        BUSINESS_ID,
        Instant.ofEpochMilli(1_750_000_000_000L),
        shrinkageMinor,
        "IDR");
  }

  /** Makes {@code processOnce} run its handler (a first delivery) and report it posted. */
  private void firstDelivery() {
    doAnswer(
            invocation -> {
              invocation.getArgument(1, Runnable.class).run();
              return true;
            })
        .when(processedEvents)
        .processOnce(any(), any());
  }

  /** Stubs the GL single-base-currency guard's read to report no divergence. */
  @SuppressWarnings("unchecked")
  private void noGlCurrencyDivergence() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
  }

  private void mapInventoryAccounts() {
    when(resolver.resolve(eq(AccountRole.INVENTORY), any())).thenReturn("1100");
    when(resolver.resolve(eq(AccountRole.INVENTORY_SHRINKAGE), any())).thenReturn("5800");
  }

  // ---- buildEntry (pure) -------------------------------------------------------------------

  @Test
  void lossDebitsShrinkageAndCreditsInventory() {
    mapInventoryAccounts();

    StocktakeCompletedEvent event = event(75_000L);
    JournalEntry entry = writer().buildEntry(event, UUID.randomUUID(), "2026-08");

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(lines.get(0).getAccountCode()).isEqualTo("5800");
    assertThat(lines.get(0).getDebitMinor()).isEqualTo(75_000L);
    assertThat(lines.get(1).getAccountCode()).isEqualTo("1100");
    assertThat(lines.get(1).getCreditMinor()).isEqualTo(75_000L);
    assertThat(entry.getDescription()).isEqualTo("Inventory stocktake shrinkage");
    assertThat(entry.getSourceEventId()).isEqualTo(event.eventId());
    assertThat(entry.isUsesIllustrativeRules()).isTrue();
  }

  @Test
  void gainDebitsInventoryAndCreditsShrinkage() {
    mapInventoryAccounts();

    StocktakeCompletedEvent event = event(-40_000L);
    JournalEntry entry = writer().buildEntry(event, UUID.randomUUID(), "2026-08");

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(lines.get(0).getAccountCode()).isEqualTo("1100");
    assertThat(lines.get(0).getDebitMinor()).isEqualTo(40_000L);
    assertThat(lines.get(1).getAccountCode()).isEqualTo("5800");
    assertThat(lines.get(1).getCreditMinor()).isEqualTo(40_000L);
    assertThat(entry.getDescription()).isEqualTo("Inventory stocktake gain");
  }

  @Test
  void zeroShrinkageIsRejectedByBuildEntry() {
    StocktakeCompletedEvent event = event(0L);
    assertThatThrownBy(() -> writer().buildEntry(event, UUID.randomUUID(), "2026-08"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("zero");
  }

  @Test
  void longMinValueShrinkageIsPoison() {
    StocktakeCompletedEvent event = event(Long.MIN_VALUE);
    assertThatThrownBy(() -> writer().buildEntry(event, UUID.randomUUID(), "2026-08"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("out of range");
  }

  // ---- post (read-model contribution — the W1 fix) -----------------------------------------

  @Test
  void aLossPostsThePerOutletExpenseAndTheConsolidatedPnl() throws Exception {
    firstDelivery();
    noGlCurrencyDivergence();
    mapInventoryAccounts();
    StocktakeCompletedEvent event = event(75_000L);
    String period = LedgerPosting.periodOf(event.countedAt());
    Money expected = Money.ofMinor(75_000L, "IDR");

    boolean posted = TenantContext.callAs(TENANT, ACTOR, () -> writer().post(event));
    assertThat(posted).isTrue();

    ArgumentCaptor<LedgerPosting> captor = ArgumentCaptor.forClass(LedgerPosting.class);
    verify(ledgerRepository).save(captor.capture());
    LedgerPosting saved = captor.getValue();
    assertThat(saved.getPostingType()).isEqualTo(PostingType.EXPENSE);
    assertThat(saved.getBusinessId()).isEqualTo(BUSINESS_ID);
    assertThat(saved.getGlAccountCode())
        .isEqualTo("5800"); // the sign lives in the amount, not 1100
    assertThat(saved.getAmount()).isEqualTo(expected);
    assertThat(saved.getSourceEventId()).isEqualTo(event.eventId());

    verify(pnlReadModel).addExpense(period, expected, TENANT, ACTOR, true);

    // Coherence lock: the GL journal and the read model tell the SAME story in this one post — the
    // loss DEBITS the 5800 expense leg by the magnitude, matching the positive read-model expense.
    ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
    verify(journalEntryRepository).saveAndFlush(entryCaptor.capture());
    JournalLine shrinkageLeg = entryCaptor.getValue().getLines().get(0);
    assertThat(shrinkageLeg.getAccountCode()).isEqualTo("5800");
    assertThat(shrinkageLeg.getDebitMinor()).isEqualTo(75_000L);
  }

  @Test
  void aGainPostsANegativeContraExpenseToBothReadModels() throws Exception {
    firstDelivery();
    noGlCurrencyDivergence();
    mapInventoryAccounts();
    StocktakeCompletedEvent event = event(-40_000L); // physical overage
    String period = LedgerPosting.periodOf(event.countedAt());
    Money expected = Money.ofMinor(-40_000L, "IDR"); // negative = lifts profit back up

    boolean posted = TenantContext.callAs(TENANT, ACTOR, () -> writer().post(event));
    assertThat(posted).isTrue();

    ArgumentCaptor<LedgerPosting> captor = ArgumentCaptor.forClass(LedgerPosting.class);
    verify(ledgerRepository).save(captor.capture());
    LedgerPosting saved = captor.getValue();
    assertThat(saved.getPostingType()).isEqualTo(PostingType.EXPENSE);
    assertThat(saved.getGlAccountCode()).isEqualTo("5800");
    assertThat(saved.getAmount()).isEqualTo(expected);

    verify(pnlReadModel).addExpense(period, expected, TENANT, ACTOR, true);
  }

  @Test
  void zeroShrinkageWritesNoJournalNorReadModelRows() throws Exception {
    firstDelivery();
    StocktakeCompletedEvent event = event(0L);

    boolean posted = TenantContext.callAs(TENANT, ACTOR, () -> writer().post(event));
    assertThat(posted).isTrue(); // the event is still claimed, just no money moves

    verify(ledgerRepository, never()).save(any());
    verify(journalEntryRepository, never()).saveAndFlush(any());
    verify(pnlReadModel, never()).addExpense(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void aSealedPeriodQuarantinesWithNoPostingAndNoReadModelRows() throws Exception {
    firstDelivery();
    when(ledgerRepository.sealedPeriodExists(any())).thenReturn(true);
    StocktakeCompletedEvent event = event(75_000L);

    boolean posted = TenantContext.callAs(TENANT, ACTOR, () -> writer().post(event));
    assertThat(posted).isTrue(); // claimed so redelivery cannot retry into sealed books

    verify(errorInbox).record(any(), anyString(), eq(TENANT), any());
    verify(ledgerRepository, never()).save(any());
    verify(journalEntryRepository, never()).saveAndFlush(any());
    verifyNoInteractions(pnlReadModel);
  }

  @Test
  void aRedeliveredEventIsANoOp() throws Exception {
    when(processedEvents.processOnce(any(), any())).thenReturn(false);
    StocktakeCompletedEvent event = event(75_000L);

    boolean posted = TenantContext.callAs(TENANT, ACTOR, () -> writer().post(event));

    assertThat(posted).isFalse();
    verifyNoInteractions(
        ledgerRepository, pnlReadModel, resolver, journalEntryRepository, journalLineRepository);
  }
}
