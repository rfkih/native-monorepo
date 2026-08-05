package id.co.nativeapp.restaurant.register.dto;

/**
 * One tender's expected amount in the open session's window (ADR 0038, phase 1) — the net charged
 * amount that accrued in that tender's clearing account so far ({@code sales − refunds}). A live
 * preview shown on the close screen so the cashier sees what to count/verify per tender; the
 * authoritative figures are snapshotted at close.
 *
 * @param tenderType {@code CASH} | {@code CARD} | {@code QRIS} | {@code ONLINE}
 * @param expectedMinor the expected amount, integer minor units of the session currency (rule 8)
 */
public record TenderExpected(String tenderType, long expectedMinor) {}
