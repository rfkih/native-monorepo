package id.co.nativeapp.servicetemplate;

import id.co.nativeapp.servicetemplate.config.PersistenceConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Native service template — the reusable starting point every service is cloned
 * from (M0.3).
 *
 * <p>The cross-cutting persistence wiring (Spring Data JPA auditing from {@code libs/tenant} and
 * the auto-RLS beans/aspect) lives in {@link PersistenceConfig} rather than on this bootstrap
 * class. Keeping it off the {@code @SpringBootApplication} class means web slice tests
 * ({@code @WebMvcTest}) do not drag in JPA infrastructure they have no datasource for, while the
 * full application still loads it via component scanning.
 */
@SpringBootApplication
public class ServiceTemplateApplication {

  public static void main(String[] args) {
    SpringApplication.run(ServiceTemplateApplication.class, args);
  }
}
