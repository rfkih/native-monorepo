package id.co.nativeapp.restaurant.register.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.register.domain.RegisterSession;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionIdempotencyKeyConflictException;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionNotOpenException;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionTender;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionResult;
import id.co.nativeapp.restaurant.register.dto.RegisterExpectedResponse;
import id.co.nativeapp.restaurant.register.dto.RegisterSessionResponse;
import id.co.nativeapp.restaurant.register.dto.TenderCount;
import id.co.nativeapp.restaurant.register.projection.RegisterSessionView;
import id.co.nativeapp.restaurant.register.repository.RegisterSessionRepository;
import id.co.nativeapp.restaurant.register.repository.RegisterSessionTenderRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit pins for the {@link RegisterSessionWriter} money path (ADR 0036) — the exact behaviors the
 * money review demanded: drawer-accurate expected cash (three inflow/outflow terms), the SIGNED
 * over/short, and strict idempotency (replayed key + different payload → 409, never a silent 200).
 * The repository sums are mocked scalars — the SQL windows themselves are exercised by the live
 * drill and the covering indexes proven by the V22/V23 migration verification.
 */
class RegisterSessionWriterTest {

  private static final String COMPANY = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("5f5e0167-ee70-45b8-8afe-019e8129e659");

  private final RegisterSessionRepository repository = mock(RegisterSessionRepository.class);
  private final RegisterSessionTenderRepository tenderRepository =
      mock(RegisterSessionTenderRepository.class);
  private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
  private final OutletAccessGuard guard = mock(OutletAccessGuard.class);
  private final CashWindowLock cashWindowLock = mock(CashWindowLock.class);
  private final RegisterSessionWriter writer =
      new RegisterSessionWriter(repository, tenderRepository, outboxWriter, guard, cashWindowLock);

  @BeforeEach
  void stubOutboxReturnsAnEventId() {
    // close() now records the returned outbox event id on the session (ADR 0064); the mock must
    // return a non-null UUID so recordCloseEventId() does not NPE.
    when(outboxWriter.write(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(UUID.randomUUID());
  }

  private static RegisterSession openSession(long floatMinor) {
    RegisterSession session =
        RegisterSession.open(
            OUTLET, LocalDate.of(2026, 8, 3), Instant.now(), floatMinor, "IDR", "open-key");
    session.setCompanyId(COMPANY);
    return session;
  }

  private static <T> T asTenant(java.util.concurrent.Callable<T> action) {
    try {
      return TenantContext.callAs(COMPANY, "test", action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ── close math: expected = float + (sales + gift-card cash) − refunds ─────

  @Test
  void closeComputesExpectedFromAllThreeDrawerTermsAndSignsTheVariance() {
    RegisterSession session = openSession(100_000L);
    when(repository.findViewByCloseIdempotencyKey("close-key")).thenReturn(Optional.empty());
    when(repository.findWithLockById(session.getId())).thenReturn(Optional.of(session));
    when(repository.sumCashSales(any(), any(), any())).thenReturn(70_000L);
    when(repository.sumCashGiftCardSales(any(), any(), any())).thenReturn(30_000L);
    when(repository.sumCashRefunds(any(), any(), any())).thenReturn(10_000L);
    when(repository.saveAndFlush(session)).thenReturn(session);

    RegisterSessionResponse response =
        asTenant(
            () -> writer.close(session.getId(), new CloseSessionRequest(192_000L), "close-key"));

    // expected = 100k float + (70k sales + 30k gift-card cash) − 10k refunds = 190k
    assertThat(response.expectedCashMinor()).isEqualTo(190_000L);
    assertThat(response.cashSalesMinor()).isEqualTo(100_000L);
    assertThat(response.cashRefundsMinor()).isEqualTo(10_000L);
    // counted 192k → SIGNED over/short = +2k (over)
    assertThat(response.overShortMinor()).isEqualTo(2_000L);
    assertThat(response.status()).isEqualTo(RegisterSession.STATUS_CLOSED);
    // the RegisterSessionClosed outbox row commits in the same unit of work (rule 3)
    verify(outboxWriter)
        .write(anyString(), anyString(), anyString(), any(), any(), any(), any(Instant.class));
    verify(guard).enforce(OUTLET);
    // CashWindowLock (verified HIGH race fix): the per-business advisory lock is taken exactly
    // once, before closeInstant/the cash sums are computed — see RegisterSessionWriter class doc.
    verify(cashWindowLock).acquireForClose(OUTLET);
  }

  @Test
  void closeOfAlreadyClosedSessionWithNewKeyConflicts() {
    RegisterSession session = openSession(0L);
    session.close(Instant.now(), 0L, 0L, 0L, 0L, 0L, "first-close-key");
    when(repository.findViewByCloseIdempotencyKey("second-close-key")).thenReturn(Optional.empty());
    when(repository.findWithLockById(session.getId())).thenReturn(Optional.of(session));

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        writer.close(
                            session.getId(), new CloseSessionRequest(0L), "second-close-key")))
        .isInstanceOf(RegisterSessionNotOpenException.class);
    verify(outboxWriter, never())
        .write(anyString(), anyString(), anyString(), any(), any(), any(), any(Instant.class));
  }

  // ── strict idempotency: replayed key must mean the SAME logical request ───

  @Test
  void closeReplayWithDifferentCountConflicts() {
    RegisterSessionView replayed = mock(RegisterSessionView.class);
    UUID sessionId = UUID.randomUUID();
    when(replayed.getId()).thenReturn(sessionId);
    when(replayed.getCountedCashMinor()).thenReturn(50_000L);
    when(repository.findViewByCloseIdempotencyKey("close-key")).thenReturn(Optional.of(replayed));

    assertThatThrownBy(
            () ->
                asTenant(
                    () -> writer.close(sessionId, new CloseSessionRequest(60_000L), "close-key")))
        .isInstanceOf(RegisterSessionIdempotencyKeyConflictException.class);
    verify(repository, never()).findWithLockById(any());
    // A close-key conflict short-circuits BEFORE the CashWindowLock is ever reached — an idempotent
    // replay probe never contends the business's advisory lock.
    verify(cashWindowLock, never()).acquireForClose(any());
  }

  @Test
  void openReplayWithDifferentOutletConflicts() {
    RegisterSessionView replayed = mock(RegisterSessionView.class);
    when(replayed.getBusinessId()).thenReturn(UUID.randomUUID());
    when(repository.findViewByOpenIdempotencyKey("open-key")).thenReturn(Optional.of(replayed));

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        writer.open(
                            new OpenSessionRequest(OUTLET, 100_000L, "IDR", null), "open-key")))
        .isInstanceOf(RegisterSessionIdempotencyKeyConflictException.class);
    verify(repository, never()).saveAndFlush(any());
    // A conflicting-payload replay short-circuits BEFORE the CashWindowLock is ever reached.
    verify(cashWindowLock, never()).acquireForClose(any());
  }

  @Test
  void openAcquiresTheCashWindowLockBeforeStampingOpenedAt() {
    when(repository.findViewByOpenIdempotencyKey("fresh-open-key")).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(RegisterSession.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    OpenSessionResult result =
        asTenant(
            () ->
                writer.open(
                    new OpenSessionRequest(OUTLET, 100_000L, "IDR", null), "fresh-open-key"));

    assertThat(result.created()).isTrue();
    // CashWindowLock (verified HIGH race fix, defensive open-boundary symmetry with close()): taken
    // exactly once, before the openedAt timestamp is captured — see RegisterSessionWriter class
    // doc.
    verify(cashWindowLock).acquireForClose(OUTLET);
    verify(guard).enforce(OUTLET);
  }

  // ── per-tender reconciliation at close (ADR 0038 phase 2) ────────────────

  @Test
  void closeWithNonCashTenderCountsPersistsAndSignsEachTenderVariance() {
    RegisterSession session = openSession(0L);
    when(repository.findViewByCloseIdempotencyKey("k")).thenReturn(Optional.empty());
    when(repository.findWithLockById(session.getId())).thenReturn(Optional.of(session));
    when(repository.sumCashSales(any(), any(), any())).thenReturn(0L);
    when(repository.sumCashGiftCardSales(any(), any(), any())).thenReturn(0L);
    when(repository.sumCashRefunds(any(), any(), any())).thenReturn(0L);
    // CARD expected = 800k sales − 0 refunds.
    when(repository.sumSalesByTender(any(), eq("CARD"), any(), any())).thenReturn(800_000L);
    when(repository.saveAndFlush(session)).thenReturn(session);

    CloseSessionRequest request =
        new CloseSessionRequest(0L, List.of(new TenderCount("CARD", 790_000L)));
    asTenant(() -> writer.close(session.getId(), request, "k"));

    ArgumentCaptor<RegisterSessionTender> captor =
        ArgumentCaptor.forClass(RegisterSessionTender.class);
    verify(tenderRepository).save(captor.capture());
    RegisterSessionTender saved = captor.getValue();
    assertThat(saved.getTenderType()).isEqualTo("CARD");
    assertThat(saved.getExpectedMinor()).isEqualTo(800_000L);
    assertThat(saved.getCountedMinor()).isEqualTo(790_000L);
    // counted 790k − expected 800k → SIGNED −10k (short)
    assertThat(saved.getOverShortMinor()).isEqualTo(-10_000L);
    assertThat(saved.getSessionId()).isEqualTo(session.getId());
  }

  @Test
  void closeWithoutTenderCountsPersistsNoTenderLines() {
    RegisterSession session = openSession(100_000L);
    when(repository.findViewByCloseIdempotencyKey("k")).thenReturn(Optional.empty());
    when(repository.findWithLockById(session.getId())).thenReturn(Optional.of(session));
    when(repository.sumCashSales(any(), any(), any())).thenReturn(0L);
    when(repository.sumCashGiftCardSales(any(), any(), any())).thenReturn(0L);
    when(repository.sumCashRefunds(any(), any(), any())).thenReturn(0L);
    when(repository.saveAndFlush(session)).thenReturn(session);

    asTenant(() -> writer.close(session.getId(), new CloseSessionRequest(100_000L), "k"));

    verify(tenderRepository, never()).save(any());
  }

  // ── multi-session per day (owner decision reverting ADR 0038's day-final tightening) ──────

  @Test
  void openSucceedsForAnOutletThatAlreadyHasAClosedSessionThatSameBusinessDay() {
    when(repository.findViewByOpenIdempotencyKey("fresh-open-key")).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(RegisterSession.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // Several sessions per outlet per day are legal again — the writer no longer probes for an
    // existing session on the requested business day at all (no repository call, no rejection).
    OpenSessionResult result =
        asTenant(
            () ->
                writer.open(
                    new OpenSessionRequest(OUTLET, 100_000L, "IDR", LocalDate.of(2026, 8, 6)),
                    "fresh-open-key"));

    assertThat(result.created()).isTrue();
    verify(repository).saveAndFlush(any());
  }

  // ── per-tender expected preview (ADR 0038) ───────────────────────────────

  @Test
  void expectedBreakdownComputesPerTenderExpectedForTheOpenSession() {
    UUID sessionId = UUID.randomUUID();
    RegisterSessionView view = mock(RegisterSessionView.class);
    when(view.getBusinessId()).thenReturn(OUTLET);
    when(view.getStatus()).thenReturn(RegisterSession.STATUS_OPEN);
    when(view.getOpeningFloatMinor()).thenReturn(100_000L);
    when(view.getCurrency()).thenReturn("IDR");
    when(view.getOpenedAt()).thenReturn(Instant.now().minusSeconds(3600));
    when(repository.findViewById(sessionId)).thenReturn(Optional.of(view));
    // Cash drawer terms (float + sales + gift-card cash − refunds).
    when(repository.sumCashSales(any(), any(), any())).thenReturn(70_000L);
    when(repository.sumCashGiftCardSales(any(), any(), any())).thenReturn(30_000L);
    when(repository.sumCashRefunds(any(), any(), any())).thenReturn(10_000L);
    // Non-cash per tender: net = sales − refunds.
    when(repository.sumSalesByTender(eq(OUTLET), eq("CARD"), any(), any())).thenReturn(800_000L);
    when(repository.sumSalesByTender(eq(OUTLET), eq("QRIS"), any(), any())).thenReturn(430_000L);
    when(repository.sumRefundsByTender(eq(OUTLET), eq("QRIS"), any(), any())).thenReturn(30_000L);
    when(repository.sumSalesByTender(eq(OUTLET), eq("ONLINE"), any(), any())).thenReturn(615_000L);

    RegisterExpectedResponse resp = asTenant(() -> writer.expectedBreakdown(sessionId));

    assertThat(resp.currency()).isEqualTo("IDR");
    assertThat(resp.sessionId()).isEqualTo(sessionId);
    // cash = 100k float + (70k sales + 30k gift-card) − 10k refunds = 190k
    assertThat(expectedOf(resp, "CASH")).isEqualTo(190_000L);
    assertThat(expectedOf(resp, "CARD")).isEqualTo(800_000L);
    assertThat(expectedOf(resp, "QRIS")).isEqualTo(400_000L); // 430k − 30k refund
    assertThat(expectedOf(resp, "ONLINE")).isEqualTo(615_000L);
    verify(guard).enforce(OUTLET);
  }

  private static long expectedOf(RegisterExpectedResponse response, String tender) {
    return response.tenders().stream()
        .filter(t -> t.tenderType().equals(tender))
        .findFirst()
        .orElseThrow()
        .expectedMinor();
  }

  @Test
  void openReplayWithSamePayloadReturnsExistingSession() {
    RegisterSessionView replayed = mock(RegisterSessionView.class);
    when(replayed.getId()).thenReturn(UUID.randomUUID());
    when(replayed.getBusinessId()).thenReturn(OUTLET);
    when(replayed.getOpeningFloatMinor()).thenReturn(100_000L);
    when(replayed.getCurrency()).thenReturn("IDR");
    when(replayed.getStatus()).thenReturn(RegisterSession.STATUS_OPEN);
    when(repository.findViewByOpenIdempotencyKey("open-key")).thenReturn(Optional.of(replayed));

    OpenSessionResult result =
        asTenant(
            () -> writer.open(new OpenSessionRequest(OUTLET, 100_000L, "IDR", null), "open-key"));

    assertThat(result.created()).isFalse();
    // the replay still passes the outlet-assignment gate (review W1)
    verify(guard).enforce(OUTLET);
    verify(repository, never()).saveAndFlush(any());
    // A same-payload replay is a no-op read — it never contends the business's advisory lock.
    verify(cashWindowLock, never()).acquireForClose(any());
  }
}
