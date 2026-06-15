package id.co.nativeapp.employee.payroll.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A decoded {@code PeriodSealed} reduced to what the {@link PeriodSeal} projection needs.
 *
 * @param eventId the event UUID (idempotency key)
 * @param companyId the tenant the consumer binds the write to
 * @param businessId the sealed business unit (outlet)
 * @param period the sealed period (YYYY-MM)
 * @param sealedAt when it sealed
 */
public record PeriodSealedProjectedEvent(
    UUID eventId, String companyId, UUID businessId, String period, Instant sealedAt) {}
