package id.co.nativeapp.finance.labor.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One settleable liability bucket of a {@code payroll_run_ledger} row (ADR 0032, Track P phase P5):
 * the reconstructed amount + its settlement status. {@code negative} flags the December Art-17
 * true-up refund case (ADR 0031) — a negative bucket cannot be settled standalone (see {@code
 * PayrollSettlementWriter}); the console renders the not-settleable explanation instead of a settle
 * button.
 *
 * @param kind one of {@code NET_WAGES}/{@code PPH21}/{@code BPJS_KES}/{@code BPJS_TK}/{@code OTHER}
 * @param amountMinor the reconstructed bucket amount (may be negative or zero)
 * @param currency the run's ISO-4217 currency
 * @param negative whether {@code amountMinor} is negative (not settleable in v1)
 * @param settled whether this bucket has been settled
 * @param settledAt the settlement instant, or {@code null} if unsettled
 * @param journalEntryId the settlement's journal entry id, or {@code null} if unsettled
 */
public record PayrollLiabilityBucketResponse(
    String kind,
    long amountMinor,
    String currency,
    boolean negative,
    boolean settled,
    Instant settledAt,
    UUID journalEntryId) {}
