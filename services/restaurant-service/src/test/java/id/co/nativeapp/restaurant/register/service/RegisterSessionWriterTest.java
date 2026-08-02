package id.co.nativeapp.restaurant.register.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.register.domain.RegisterSession;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionIdempotencyKeyConflictException;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionNotOpenException;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionResult;
import id.co.nativeapp.restaurant.register.dto.RegisterSessionResponse;
import id.co.nativeapp.restaurant.register.projection.RegisterSessionView;
import id.co.nativeapp.restaurant.register.repository.RegisterSessionRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
  private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
  private final OutletAccessGuard guard = mock(OutletAccessGuard.class);
  private final RegisterSessionWriter writer =
      new RegisterSessionWriter(repository, outboxWriter, guard);

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
  }
}
