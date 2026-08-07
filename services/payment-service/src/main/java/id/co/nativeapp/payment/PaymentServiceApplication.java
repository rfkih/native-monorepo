package id.co.nativeapp.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for payment-service — the single owner of QRIS payment modes and the PSP charge
 * lifecycle (ADR 0045): payment_settings (mode + static QRIS image + the merchant's own encrypted
 * Midtrans credentials) and payment_charge (dynamic QRIS per transaction, settled by the signed
 * inbound Midtrans webhook, closed by a {@code PaymentChargeSucceeded} outbox event the POS
 * verticals consume).
 *
 * <p>The cross-cutting persistence wiring (Spring Data JPA auditing and the auto-RLS beans/aspect +
 * pinned tx-advisor order) is contributed by {@code libs/tenant}'s {@code
 * TenantRlsAutoConfiguration}, and the shared RFC-7807 {@code ProblemDetail} advice + JWT chain by
 * {@code libs/security} — both Spring Boot auto-configurations inherited purely by depending on
 * those libraries, with no copied {@code config} class.
 */
@SpringBootApplication
public class PaymentServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentServiceApplication.class, args);
  }
}
