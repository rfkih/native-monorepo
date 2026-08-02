package id.co.nativeapp.restaurant.register.dto;

/**
 * Request to close a drawer session with the cashier's physical count (ADR 0036).
 *
 * @param countedCashMinor the whole-drawer count INCLUDING the float, minor units (≥ 0). This is
 *     the only client-supplied figure at close — expected cash and the variance are server-computed
 */
public record CloseSessionRequest(long countedCashMinor) {}
