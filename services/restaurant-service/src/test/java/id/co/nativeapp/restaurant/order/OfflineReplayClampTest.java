package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OfflineReplayGuard;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.register.projection.RegisterSessionView;
import id.co.nativeapp.restaurant.register.repository.RegisterSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 2026-08-31 audit #5 — an offline replay backdated BEFORE the current OPEN register session's
 * window used to land in NO session at all (its own session had already closed and summed its cash
 * without it), permanently understating the drawer's expected cash. The guard now clamps such a
 * {@code clientOccurredAt} to the open session's {@code openedAt} — the physical cash IS in this
 * drawer, so the sale reconciles in the session that will actually count the money. With no OPEN
 * session, or a {@code clientOccurredAt} inside the window, the instant is untouched.
 *
 * <p>Pure unit test (mocked repository) — the bounds/field-matrix contract is covered by {@code
 * OfflineReplayCheckoutTest}.
 */
class OfflineReplayClampTest {

  private static final UUID BUSINESS = UUID.randomUUID();

  private final RegisterSessionRepository sessions = mock(RegisterSessionRepository.class);
  private final OfflineReplayGuard guard = new OfflineReplayGuard(sessions);

  private CheckoutRequest replayAt(Instant clientOccurredAt) {
    return new CheckoutRequest(
        BUSINESS,
        "replay-clamp-key",
        List.of(new OrderLineRequest(UUID.randomUUID(), 1)),
        new PaymentRequest(TenderType.CASH, 50_000L),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        true, // offlineReplay
        clientOccurredAt,
        null);
  }

  private void openSessionAt(Instant openedAt) {
    RegisterSessionView view = mock(RegisterSessionView.class);
    when(view.getOpenedAt()).thenReturn(openedAt);
    when(sessions.findOpenViewByBusinessId(any())).thenReturn(Optional.of(view));
  }

  @Test
  void backdatedBeforeTheOpenSessionIsClampedToItsStart() {
    Instant now = Instant.parse("2026-08-31T10:00:00Z");
    Instant openedAt = Instant.parse("2026-08-31T08:00:00Z");
    Instant backdated = Instant.parse("2026-08-31T06:30:00Z"); // before openedAt, within 48h
    openSessionAt(openedAt);

    assertThat(guard.resolveOccurredAt(replayAt(backdated), now)).isEqualTo(openedAt);
  }

  @Test
  void backdatedInsideTheOpenSessionWindowIsUntouched() {
    Instant now = Instant.parse("2026-08-31T10:00:00Z");
    Instant openedAt = Instant.parse("2026-08-31T08:00:00Z");
    Instant inside = Instant.parse("2026-08-31T09:15:00Z");
    openSessionAt(openedAt);

    assertThat(guard.resolveOccurredAt(replayAt(inside), now)).isEqualTo(inside);
  }

  @Test
  void withNoOpenSessionTheBackdatedInstantIsUntouched() {
    Instant now = Instant.parse("2026-08-31T10:00:00Z");
    Instant backdated = Instant.parse("2026-08-31T06:30:00Z");
    when(sessions.findOpenViewByBusinessId(any())).thenReturn(Optional.empty());

    assertThat(guard.resolveOccurredAt(replayAt(backdated), now)).isEqualTo(backdated);
  }

  @Test
  void theNormalOnlinePathNeverTouchesTheSessionLookup() {
    Instant now = Instant.parse("2026-08-31T10:00:00Z");
    CheckoutRequest online =
        new CheckoutRequest(
            BUSINESS,
            "online-key",
            List.of(new OrderLineRequest(UUID.randomUUID(), 1)),
            new PaymentRequest(TenderType.CASH, 50_000L));

    assertThat(guard.resolveOccurredAt(online, now)).isEqualTo(now);
  }
}
