package id.co.nativeapp.loyalty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for loyalty-service — Phase 4 of the POS-parity program (ADR 0027): the sole owner of
 * the loyalty points / gift-card ledger of record, and the ONLY home of member PII (phone, display
 * name — column-level encrypted). Enrolls/looks up members, resolves the company-wide earn rule,
 * serves gift-card lookups, and idempotently ingests {@code SaleRecorded}/{@code
 * GiftCardSold}/{@code SaleVoided}/{@code SaleRefunded} to apply earn/redeem/reversal and emit
 * {@code LoyaltyBalanceChanged}/{@code GiftCardStateChanged}/{@code LoyaltyRedemptionFlagged} via
 * the outbox.
 *
 * <p>The cross-cutting persistence wiring (Spring Data JPA auditing and the auto-RLS beans/aspect +
 * pinned tx-advisor order) is contributed by {@code libs/tenant}'s {@code
 * TenantRlsAutoConfiguration}, and the shared RFC-7807 {@code ProblemDetail} advice by {@code
 * libs/security} — both Spring Boot auto-configurations this service inherits purely by depending
 * on those libraries, with no copied {@code config} class. The auto-config's aspect is
 * {@code @ConditionalOnBean(DataSource.class)}, so a web slice test ({@code @WebMvcTest}) with no
 * datasource is unaffected, while the full application loads it.
 *
 * <p>Unlike the verticals it mirrors (barbershop/carwash), loyalty-service is NOT entitlement-gated
 * this phase — no {@code libs/entitlement-check} dependency, no Redis.
 */
@SpringBootApplication
public class LoyaltyServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(LoyaltyServiceApplication.class, args);
  }
}
