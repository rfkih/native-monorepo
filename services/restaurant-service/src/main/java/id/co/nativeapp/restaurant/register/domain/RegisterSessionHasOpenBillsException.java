package id.co.nativeapp.restaurant.register.domain;

import java.util.UUID;

/**
 * Closing a session while bills at the outlet are still OPEN (ADR 0086) — the owner's rule: no open
 * bill survives a close; each one is paid or cancelled first, never swept away by the close itself.
 * Raised under the exclusive {@code CashWindowLock} BEFORE anything is written, so the close key is
 * not consumed and the same request replays cleanly once the bills are settled. Mapped to {@code
 * 409 Conflict} ({@code register-session-open-bills}, carrying {@code openBillCount}) by {@code
 * RegisterAdvice}.
 */
public class RegisterSessionHasOpenBillsException extends RuntimeException {

  private final UUID sessionId;
  private final UUID businessId;
  private final long openBillCount;

  public RegisterSessionHasOpenBillsException(UUID sessionId, UUID businessId, long openBillCount) {
    super(
        "register session "
            + sessionId
            + " cannot close: "
            + openBillCount
            + " bill(s) still OPEN at outlet "
            + businessId
            + " — pay or cancel them first");
    this.sessionId = sessionId;
    this.businessId = businessId;
    this.openBillCount = openBillCount;
  }

  public UUID getSessionId() {
    return sessionId;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public long getOpenBillCount() {
    return openBillCount;
  }
}
