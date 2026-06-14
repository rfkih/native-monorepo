package id.co.nativeapp.servicetemplate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Native service template — the reusable starting point every service is cloned
 * from (M0.3).
 *
 * <p>The cross-cutting persistence wiring (Spring Data JPA auditing and the auto-RLS beans/aspect +
 * pinned tx-advisor order) is now contributed by {@code libs/tenant}'s {@code
 * TenantRlsAutoConfiguration}, and the shared RFC-7807 {@code ProblemDetail} advice by {@code
 * libs/security} — both Spring Boot auto-configurations a service inherits purely by depending on
 * those libraries, with no copied {@code config} class. The auto-config is
 * {@code @ConditionalOnBean(DataSource.class)} for the aspect, so a web slice test
 * ({@code @WebMvcTest}) with no datasource is unaffected, while the full application loads it.
 */
@SpringBootApplication
public class ServiceTemplateApplication {

  public static void main(String[] args) {
    SpringApplication.run(ServiceTemplateApplication.class, args);
  }
}
