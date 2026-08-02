package id.co.nativeapp.finance.empexpense.messaging;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The decoded {@code ExpenseClaimVoided} event (ADR 0030) — the application command the consumer
 * hands to {@link id.co.nativeapp.finance.empexpense.service.ExpenseClaimVoidWriter}. An immutable
 * record carrying exactly the fields finance needs, already parsed out of the raw Avro {@link
 * org.apache.avro.generic.GenericRecord}.
 *
 * <p>{@code orgUnitId} is the SAME dimension the original approval posted under — the contra hits
 * that same {@code business_id}. {@code glHint} + {@code approvedAt} together let finance resolve
 * the EXACT account the approval posted to (the mapping_rule effective AT approval), even if the
 * mapping has since changed; {@code voidedAt} drives the accounting period the contra posts into
 * (the reversal precedent — current open period, not backdated to the approval's period).
 *
 * @param eventId the source event UUID (idempotency key)
 * @param claimId the expense-claim aggregate id
 * @param companyId the owning tenant the consumer binds the handler to (from the event)
 * @param orgUnitId the dimension the approval posted under (all-zeros sentinel when unassigned)
 * @param employeeId the claiming employee (a UUID reference, not PII)
 * @param amount the exact approved amount being contra'd
 * @param glHint the approval's category hint — the contra resolves the same expense account
 * @param approvedAt the ORIGINAL approval instant — the mapping resolves effective as-of here
 * @param voidedAt when the void happened — drives the period the contra posts into
 */
public record ExpenseClaimVoidedEvent(
    UUID eventId,
    UUID claimId,
    String companyId,
    UUID orgUnitId,
    UUID employeeId,
    Money amount,
    String glHint,
    Instant approvedAt,
    Instant voidedAt) {}
