package id.co.nativeapp.restaurant.register.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The live per-tender EXPECTED breakdown for an OPEN register session (ADR 0038, phase 1). Computed
 * on demand over {@code [opened_at, asOf)} so the close screen can show, for each tender, what the
 * ledger says should be there before the cashier enters the counts. A preview only — the close
 * snapshots the authoritative figures.
 *
 * @param sessionId the open session
 * @param businessId the outlet
 * @param currency ISO-4217 code of the drawer
 * @param asOf the instant the breakdown was computed (the window upper bound)
 * @param tenders one row per tender (CASH/CARD/QRIS/ONLINE), expected in minor units
 * @param openBillCount OPEN bills at the outlet at {@code asOf} — the close precondition (ADR
 *     0086): the close refuses ({@code 409 register-session-open-bills}) while this is {@code > 0},
 *     so the close screen can say so before the cashier counts
 */
public record RegisterExpectedResponse(
    UUID sessionId,
    UUID businessId,
    String currency,
    Instant asOf,
    List<TenderExpected> tenders,
    long openBillCount) {}
