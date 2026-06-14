package id.co.nativeapp.finance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for finance-service (M1.5) — the financial core, a PURELY DOWNSTREAM consumer. It
 * calls no other service: it consumes the {@code SaleRecorded} event off Kafka, posts an
 * append-only {@code ledger_posting} (Money in the transaction currency), accumulates the {@code
 * consolidated_revenue} read model, and exposes a tenant-scoped revenue query API. Single base
 * currency, NO FX yet.
 *
 * <p>The cross-cutting persistence wiring (Spring Data JPA auditing and the auto-RLS beans/aspect +
 * pinned tx-advisor order) is now contributed by {@code libs/tenant}'s {@code
 * TenantRlsAutoConfiguration}, and the shared RFC-7807 {@code ProblemDetail} advice by {@code
 * libs/security} — both Spring Boot auto-configurations this service inherits purely by depending
 * on those libraries, with no copied {@code config} class. The one finance-specific fault shape (a
 * malformed request <em>parameter</em> → {@code 400}) is added by {@code
 * config/ConstraintViolationAdvice}. The auto-config's aspect is
 * {@code @ConditionalOnBean(DataSource.class)}, so a web slice test ({@code @WebMvcTest}) with no
 * datasource is unaffected, while the full application loads it.
 */
@SpringBootApplication
public class FinanceServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(FinanceServiceApplication.class, args);
  }
}
