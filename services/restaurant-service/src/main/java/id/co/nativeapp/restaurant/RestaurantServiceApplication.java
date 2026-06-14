package id.co.nativeapp.restaurant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for restaurant-service (M1.4) — the first vertical and the validation-slice goal:
 * record a sale, emit {@code SaleRecorded} via the outbox.
 *
 * <p>The cross-cutting persistence wiring (Spring Data JPA auditing and the auto-RLS beans/aspect +
 * pinned tx-advisor order) is now contributed by {@code libs/tenant}'s {@code
 * TenantRlsAutoConfiguration}, and the shared RFC-7807 {@code ProblemDetail} advice by {@code
 * libs/security} — both Spring Boot auto-configurations this service inherits purely by depending
 * on those libraries, with no copied {@code config} class. The auto-config's aspect is
 * {@code @ConditionalOnBean(DataSource.class)}, so a web slice test ({@code @WebMvcTest}) with no
 * datasource is unaffected, while the full application loads it.
 */
@SpringBootApplication
public class RestaurantServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(RestaurantServiceApplication.class, args);
  }
}
