package id.co.nativeapp.org;

import id.co.nativeapp.org.config.PersistenceConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for org-service (M1.2) — the tenant keystone: create one company (the legal employer)
 * with its immutable {@code base_currency} and {@code default_language} plus one business, and emit
 * {@code CompanyCreated} via the outbox.
 *
 * <p>The cross-cutting persistence wiring (Spring Data JPA auditing from {@code libs/tenant} and
 * the auto-RLS beans/aspect) lives in {@link PersistenceConfig} rather than on this bootstrap
 * class. Keeping it off the {@code @SpringBootApplication} class means web slice tests
 * ({@code @WebMvcTest}) do not drag in JPA infrastructure they have no datasource for, while the
 * full application still loads it via component scanning.
 */
@SpringBootApplication
public class OrgServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrgServiceApplication.class, args);
  }
}
