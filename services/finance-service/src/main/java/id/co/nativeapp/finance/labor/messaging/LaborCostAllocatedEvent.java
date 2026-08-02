package id.co.nativeapp.finance.labor.messaging;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The decoded {@code LaborCostAllocated} event (#23) — one (outlet, gl_account) bucket the consumer
 * hands to the labor-cost posting service. An immutable record carrying exactly the fields finance
 * needs, already parsed out of the raw Avro {@link org.apache.avro.generic.GenericRecord}.
 *
 * <p>Each event becomes exactly one EXPENSE {@link
 * id.co.nativeapp.finance.revenue.domain.LedgerPosting}. The {@code glAccount} is treated as a HINT
 * (finance re-resolves the canonical account, CQRS); {@code amount} is the bucket's aggregated
 * employer-borne labor cost as {@link Money} (never a float, rule 8). {@code period} is the run's
 * AUTHORITATIVE period (not derived from {@code occurredAt}); {@code occurredAt} drives only the
 * effective mapping_rule version.
 *
 * <p>{@code employee_id} is intentionally absent from the wire (per-person amounts would leak
 * salary — rule 6); finance is fully served by the per-(outlet, gl) granularity.
 *
 * @param eventId the source event UUID (idempotency key; UNIQUE on {@code ledger_posting})
 * @param companyId the owning tenant the consumer binds the handler to (from the event, not a
 *     request)
 * @param payrollRunId the owning payroll run
 * @param runSeq the run sequence (the supersession signal; a higher seq of the SAME runType
 *     supersedes lower ones)
 * @param runType the payroll run type ({@code REGULAR} today; {@code THR} lands at Track P phase
 *     P8, ADR 0035 — added backward-compatibly with a {@code REGULAR} default, ADR 0032 Track P
 *     phase P4)
 * @param period the run's authoritative accounting period {@code YYYY-MM}
 * @param outletId the bucket's outlet (the all-zeros sentinel for the UNALLOCATED suspense bucket)
 * @param glAccount the labor GL account hint (re-resolved against {@code mapping_rule})
 * @param amount the bucket's labor cost as {@link Money}
 * @param usesIllustrativeRules whether the figure is placeholder-derived (stamped onto the posting
 *     + OR-ed into the P&amp;L)
 * @param unallocated whether this is the UNALLOCATED labor-suspense bucket
 * @param occurredAt when the run posted (drives the effective rule version)
 */
public record LaborCostAllocatedEvent(
    UUID eventId,
    String companyId,
    UUID payrollRunId,
    int runSeq,
    String runType,
    String period,
    UUID outletId,
    String glAccount,
    Money amount,
    boolean usesIllustrativeRules,
    boolean unallocated,
    Instant occurredAt) {}
