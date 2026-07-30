package id.co.nativeapp.barbershop.entitlement.domain;

/**
 * Thrown by {@link id.co.nativeapp.barbershop.catalog.service.CatalogService} / {@link
 * id.co.nativeapp.barbershop.ticket.service.TicketService} when a company is NOT entitled to the
 * {@code barbershop} module and tries to write a catalog row or check out/capture a ticket — the
 * "real gating in the verticals" (Phase-2). It maps to {@code 403 Forbidden} via {@link
 * id.co.nativeapp.barbershop.config.NotEntitledAdvice} (a tenant/role denial, ENGINEERING-STANDARDS
 * §1.1 status table). No row is written and no event is emitted on this path — the gate is checked
 * BEFORE the transactional unit of work.
 *
 * <p><strong>Placement note (deliberate difference from carwash-service).</strong> carwash-service
 * anchors this exception in its legacy {@code wash.domain} package even though it is thrown by both
 * catalog and ticket writes. barbershop-service has no {@code wash}-analog legacy feature, so this
 * type lives in {@code entitlement.domain} instead — the bounded concept it actually belongs to —
 * and both {@code catalog} and {@code ticket} depend on it, exactly as they already depend on
 * {@code entitlement.service.EntitlementChecker} for the gate check itself.
 */
public class NotEntitledException extends RuntimeException {

  public NotEntitledException(String moduleKey) {
    super("Company is not entitled to the '" + moduleKey + "' module");
  }
}
