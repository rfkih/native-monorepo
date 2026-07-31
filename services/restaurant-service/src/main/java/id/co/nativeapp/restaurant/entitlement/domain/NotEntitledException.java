package id.co.nativeapp.restaurant.entitlement.domain;

/**
 * Thrown when a company is NOT entitled to a gated module and tries to use it — today, {@code
 * self_order} (Phase 6, ADR 0029: {@code POST /api/v1/self-order/orders}). Maps to {@code 403
 * Forbidden} via {@link id.co.nativeapp.restaurant.config.NotEntitledAdvice}. No row is written and
 * no event is emitted on this path — the gate is checked BEFORE the transactional unit of work.
 *
 * <p>Mirrors barbershop-service's/carwash-service's identically-named exception exactly.
 */
public class NotEntitledException extends RuntimeException {

  public NotEntitledException(String moduleKey) {
    super("Company is not entitled to the '" + moduleKey + "' module");
  }
}
