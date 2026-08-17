package id.co.nativeapp.employee.payroll.messaging;

import java.util.UUID;

/**
 * The decoded {@code CompanyCreated} event — employee-service's payroll-bootstrap consumer view. An
 * immutable record carrying exactly what the go-live auto-bootstrap needs from the org-service
 * contract: the source event id (idempotency key), the new company id (the tenant the consumer
 * binds the seed transaction to), and the company's base currency.
 *
 * <p>Unlike entitlement-service's consumer view, this one KEEPS {@code base_currency}: it is the
 * Indonesia gate. The canned OFFICIAL statutory dataset (BPJS / PTKP / PPh21 TER) is Indonesian
 * statutory law, and Native is multi-country (ADR 0059), so only an {@code IDR} company — the
 * established Indonesia proxy (ADR 0025: country ID → IDR) — is auto-seeded. {@code company_id} is
 * taken from the event, never a request (there is no JWT on the consumer path); the consumer binds
 * it via {@code TenantContext.callAs} so RLS applies.
 */
public record CompanyCreatedEvent(UUID eventId, String companyId, String baseCurrency) {}
