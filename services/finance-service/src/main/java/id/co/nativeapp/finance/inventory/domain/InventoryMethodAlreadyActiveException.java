package id.co.nativeapp.finance.inventory.domain;

/**
 * A company that already activated perpetual inventory (ADR 0067 §5) tried to activate again —
 * under a DIFFERENT Idempotency-Key, or the SAME key with a DIFFERENT payload. Activation is
 * once-only per company (no deactivate/amend flow), so this surfaces as {@code 409}, never a second
 * opening-balance posting. (A same-key, same-payload retry replays {@code 200} instead — see {@link
 * id.co.nativeapp.finance.inventory.service.InventoryMethodActivationWriter}.)
 */
public class InventoryMethodAlreadyActiveException extends RuntimeException {

  public InventoryMethodAlreadyActiveException(String message) {
    super(message);
  }
}
