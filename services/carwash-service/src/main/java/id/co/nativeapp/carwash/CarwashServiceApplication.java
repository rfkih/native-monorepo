package id.co.nativeapp.carwash;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for carwash-service (#20) — the 2nd vertical: record a wash, emit {@code
 * SaleRecorded} (so finance consolidates carwash revenue alongside restaurant) + {@code
 * MetricPublished} via the outbox, gated by the carwash entitlement, with a local staff read model
 * from {@code EmployeeChanged}/{@code AssignmentChanged}.
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
public class CarwashServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(CarwashServiceApplication.class, args);
  }
}
