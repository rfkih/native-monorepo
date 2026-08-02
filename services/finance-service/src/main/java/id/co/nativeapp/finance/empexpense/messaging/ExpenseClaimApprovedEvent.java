package id.co.nativeapp.finance.empexpense.messaging;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The decoded {@code ExpenseClaimApproved} event (ADR 0030) — the application command the consumer
 * hands to {@link id.co.nativeapp.finance.empexpense.service.ExpenseClaimPostingWriter}. An
 * immutable record carrying exactly the fields finance needs from the contract, already parsed out
 * of the raw Avro {@link org.apache.avro.generic.GenericRecord}.
 *
 * <p>{@code companyId} is the tenant the consumer binds the handler to (via {@code
 * TenantContext.callAs}); it is carried on the event, never taken from a request. {@code orgUnitId}
 * drives {@code ledger_posting.business_id} (the all-zeros sentinel when the employee had no active
 * assignment at {@code expense_date}). {@code employeeId} is a UUID reference, not PII (contrast
 * {@code LaborCostAllocated}, which drops it). {@code glHint} drives the dimensional expense
 * account resolution via {@code mapping_rule} (suspense fail-safe); {@code expenseDate} is
 * informational — {@code approvedAt} drives both the accounting period and the effective {@code
 * mapping_rule} version (recognition at approval).
 *
 * @param eventId the source event UUID (idempotency key; UNIQUE on {@code ledger_posting} + {@code
 *     journal_entry})
 * @param claimId the expense-claim aggregate id
 * @param companyId the owning tenant the consumer binds the handler to (from the event)
 * @param orgUnitId the claim's BU/outlet dimension (all-zeros sentinel when unassigned)
 * @param employeeId the claiming employee (a UUID reference, not PII)
 * @param amount the claim amount as {@link Money} (never a float)
 * @param glHint the category expense hint (re-resolved by finance, CQRS)
 * @param expenseDate the date the expense was incurred (informational)
 * @param approvedAt when the claim was approved — drives the period + the effective mapping version
 */
public record ExpenseClaimApprovedEvent(
    UUID eventId,
    UUID claimId,
    String companyId,
    UUID orgUnitId,
    UUID employeeId,
    Money amount,
    String glHint,
    LocalDate expenseDate,
    Instant approvedAt) {}
