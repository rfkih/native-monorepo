package id.co.nativeapp.org;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for org-service (M1.2) — the tenant keystone: create one company (the legal employer)
 * with its immutable {@code base_currency} and {@code default_language} plus one business, and emit
 * {@code CompanyCreated} via the outbox.
 *
 * <p>The cross-cutting persistence wiring (Spring Data JPA auditing and the auto-RLS beans/aspect +
 * pinned tx-advisor order) is now contributed by {@code libs/tenant}'s {@code
 * TenantRlsAutoConfiguration}, and the shared RFC-7807 {@code ProblemDetail} advice by {@code
 * libs/security} — both Spring Boot auto-configurations this service inherits purely by depending
 * on those libraries, with no copied {@code config} class. The one org-specific fault shape (a
 * tenant mismatch → {@code 403}) is added by {@code config/TenantAccessDeniedAdvice}. The
 * auto-config's aspect is {@code @ConditionalOnBean(DataSource.class)}, so a web slice test
 * ({@code @WebMvcTest}) with no datasource is unaffected, while the full application loads it.
 */
@SpringBootApplication
public class OrgServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrgServiceApplication.class, args);
  }
}
