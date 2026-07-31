package id.co.nativeapp.restaurant.selforder.domain;

import java.util.UUID;

/**
 * Thrown when an outlet's UNCONFIRMED ({@code PARKED}, {@code source=SELF_ORDER}) order count is
 * already at/over the configured ceiling ({@code native.self-order.park-cap}, default 50) when a
 * new self-order-create arrives (Phase 6, ADR 0029). Maps to {@code 429 Too Many Requests} via
 * {@code config.SelfOrderAdvice} — bounds the junk-row blast radius of an abused or leaked QR
 * without ever touching money (parking never writes a sale/payment/outbox row in the first place).
 */
public class SelfOrderCapExceededException extends RuntimeException {

  private final UUID businessId;
  private final long unconfirmedCount;
  private final int cap;

  public SelfOrderCapExceededException(UUID businessId, long unconfirmedCount, int cap) {
    super(
        "Outlet "
            + businessId
            + " already has "
            + unconfirmedCount
            + " unconfirmed self-order rows (cap "
            + cap
            + "); the cashier must confirm/clear the ParkedTray before more can be created");
    this.businessId = businessId;
    this.unconfirmedCount = unconfirmedCount;
    this.cap = cap;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public long getUnconfirmedCount() {
    return unconfirmedCount;
  }

  public int getCap() {
    return cap;
  }
}
