package id.co.nativeapp.barbershop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for barbershop-service — Phase 2 of the POS-parity program (ADR 0024): the 3rd
 * vertical. Check out a ticket, emit {@code SaleRecorded} (so finance consolidates barbershop
 * revenue alongside restaurant + carwash) + {@code MetricPublished} via the outbox, gated by the
 * barbershop entitlement, with a local staff read model from {@code EmployeeChanged}/{@code
 * AssignmentChanged}.
 *
 * <p>The cross-cutting persistence wiring (Spring Data JPA auditing and the auto-RLS beans/aspect +
 * pinned tx-advisor order) is contributed by {@code libs/tenant}'s {@code
 * TenantRlsAutoConfiguration}, and the shared RFC-7807 {@code ProblemDetail} advice by {@code
 * libs/security} — both Spring Boot auto-configurations this service inherits purely by depending
 * on those libraries, with no copied {@code config} class. The auto-config's aspect is
 * {@code @ConditionalOnBean(DataSource.class)}, so a web slice test ({@code @WebMvcTest}) with no
 * datasource is unaffected, while the full application loads it.
 */
@SpringBootApplication
public class BarbershopServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(BarbershopServiceApplication.class, args);
  }
}
