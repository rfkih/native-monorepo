package id.co.nativeapp.finance.empexpense.messaging;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The decoded {@code ExpenseReimbursementSettled} event (ADR 0030) — the application command the
 * consumer hands to {@link id.co.nativeapp.finance.empexpense.service.ExpenseSettlementWriter}. An
 * immutable record carrying exactly the fields finance needs, already parsed out of the raw Avro
 * {@link org.apache.avro.generic.GenericRecord}.
 *
 * <p>{@code settlementKind} is {@code "DIRECT"} or {@code "PAYROLL"}; {@code payrollRunId}/{@code
 * runSeq} are {@code null} for DIRECT and set for PAYROLL. {@code amount} always equals the full
 * approved claim amount (settlement never partial-pays). Finance settles ONCE per claim — a second
 * settlement for the same {@code claimId} (a Kafka re-delivery, or a re-emission after payroll
 * supersession released and re-linked the claim) is a logged no-op (ADR 0030 §7).
 *
 * @param eventId the source event UUID (idempotency key — deduped via {@code processed_event}; NOT
 *     the settle-once key, which is {@code claimId})
 * @param claimId the expense-claim aggregate id — the settle-once key ({@code
 *     employee_expense_claim_ledger} UNIQUE {@code (company_id, claim_id)})
 * @param companyId the owning tenant the consumer binds the handler to (from the event)
 * @param orgUnitId the claim's dimension (all-zeros sentinel when unassigned)
 * @param employeeId the reimbursed employee (a UUID reference, not PII)
 * @param amount the settled amount — the full approved claim amount
 * @param settlementKind {@code "DIRECT"} or {@code "PAYROLL"}
 * @param payrollRunId the settling payroll run when PAYROLL; {@code null} for DIRECT
 * @param runSeq the settling run's supersession sequence when PAYROLL; {@code null} for DIRECT
 * @param settledAt when the settlement happened — drives the settlement entry's period
 */
public record ExpenseReimbursementSettledEvent(
    UUID eventId,
    UUID claimId,
    String companyId,
    UUID orgUnitId,
    UUID employeeId,
    Money amount,
    String settlementKind,
    UUID payrollRunId,
    Integer runSeq,
    Instant settledAt) {}
