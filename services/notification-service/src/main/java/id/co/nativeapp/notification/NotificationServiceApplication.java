package id.co.nativeapp.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for notification-service (#22) — the platform notification plane.
 *
 * <p>It CONSUMES a trigger event ({@code ConsolidationClosed} from finance), idempotently CREATES a
 * notification, DELIVERS it through a stubbed transport (a {@code NotificationSender}; the real
 * SMTP/push provider is a future integration selected by profile/property), persists a {@code
 * delivery_receipt}, and PUBLISHES a {@code DeliveryReceipt} event via the transactional outbox
 * (rule 3) — all coherently in one tenant-scoped transaction.
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
public class NotificationServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(NotificationServiceApplication.class, args);
  }
}
