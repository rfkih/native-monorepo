package id.co.nativeapp.restaurant.order;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.service.OfflineReplayGuard;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * 2026-08-31 audit #5 — an offline replay backdated BEFORE the current OPEN register session's
 * window used to land in NO session at all (its own session had already closed and summed its cash
 * without it), permanently understating the drawer's expected cash. The guard now clamps such a
 * {@code clientOccurredAt} to the open session's {@code openedAt} — the physical cash IS in this
 * drawer, so the sale reconciles in the session that will actually count the money. With no OPEN
 * session, or a {@code clientOccurredAt} inside the window, the instant is untouched.
 *
 * <p>Pure unit test — the open session's {@code openedAt} arrives via the guard's lazy supplier
 * (the caller-side repository wiring is covered by {@code OfflineReplayCheckoutTest}).
 */
class OfflineReplayClampTest {

  private static final UUID BUSINESS = UUID.randomUUID();

  /** A supplier that must never be consulted — proves the lookup is lazy. */
  private static final Supplier<Optional<Instant>> NEVER_CALLED =
      () -> {
        throw new AssertionError("the open-session lookup must not run on this path");
      };

  private final OfflineReplayGuard guard = new OfflineReplayGuard();

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

  @Test
  void backdatedBeforeTheOpenSessionIsClampedToItsStart() {
    Instant now = Instant.parse("2026-08-31T10:00:00Z");
    Instant openedAt = Instant.parse("2026-08-31T08:00:00Z");
    Instant backdated = Instant.parse("2026-08-31T06:30:00Z"); // before openedAt, within 48h

    assertThat(guard.resolveOccurredAt(replayAt(backdated), now, () -> Optional.of(openedAt)))
        .isEqualTo(openedAt);
  }

  @Test
  void backdatedInsideTheOpenSessionWindowIsUntouched() {
    Instant now = Instant.parse("2026-08-31T10:00:00Z");
    Instant openedAt = Instant.parse("2026-08-31T08:00:00Z");
    Instant inside = Instant.parse("2026-08-31T09:15:00Z");

    assertThat(guard.resolveOccurredAt(replayAt(inside), now, () -> Optional.of(openedAt)))
        .isEqualTo(inside);
  }

  @Test
  void withNoOpenSessionTheBackdatedInstantIsUntouched() {
    Instant now = Instant.parse("2026-08-31T10:00:00Z");
    Instant backdated = Instant.parse("2026-08-31T06:30:00Z");

    assertThat(guard.resolveOccurredAt(replayAt(backdated), now, Optional::empty))
        .isEqualTo(backdated);
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

    assertThat(guard.resolveOccurredAt(online, now, NEVER_CALLED)).isEqualTo(now);
  }

  @Test
  void aReplayWithoutAClientInstantNeverTouchesTheSessionLookup() {
    Instant now = Instant.parse("2026-08-31T10:00:00Z");

    assertThat(guard.resolveOccurredAt(replayAt(null), now, NEVER_CALLED)).isEqualTo(now);
  }
}
