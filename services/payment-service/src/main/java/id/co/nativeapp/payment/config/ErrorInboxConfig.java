package id.co.nativeapp.payment.config;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.errorinbox.ErrorMessageRedactor;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit error-inbox wiring for a NON-Kafka service. {@code libs/error-inbox}'s
 * auto-configuration is {@code @ConditionalOnClass(ConsumerRecord)} — it assumes an event-CONSUMING
 * service — and payment-service is the fleet's first error-inbox user with no Kafka on the
 * classpath (it produces via the outbox only; the inbox parks WEBHOOK anomalies: unknown order,
 * amount mismatch, late settlement — ADR 0045). This declares exactly the subset {@link
 * id.co.nativeapp.payment.charge.service.WebhookService} needs; the beans mirror the
 * auto-configuration's construction one-for-one, and being plain {@code @Bean}s they'd also win
 * over it if the condition ever became true.
 */
@Configuration
public class ErrorInboxConfig {

  /** Bounded timeout (seconds) for the error-inbox upsert — the auto-configuration's constant. */
  private static final int ERROR_INBOX_TX_TIMEOUT_SECONDS = 5;

  @Bean
  public ErrorMessageRedactor errorMessageRedactor() {
    return new ErrorMessageRedactor();
  }

  @Bean
  public Clock errorInboxClock() {
    return Clock.systemUTC();
  }

  @Bean
  public TransactionTemplate errorInboxTransactionTemplate(PlatformTransactionManager txManager) {
    TransactionTemplate template = new TransactionTemplate(txManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setTimeout(ERROR_INBOX_TX_TIMEOUT_SECONDS);
    return template;
  }

  @Bean
  public ErrorInboxWriter errorInboxWriter(
      ErrorMessageRedactor redactor,
      JdbcTemplate jdbcTemplate,
      TransactionTemplate errorInboxTransactionTemplate,
      Clock errorInboxClock) {
    return new ErrorInboxWriter(
        redactor, jdbcTemplate, errorInboxTransactionTemplate, errorInboxClock);
  }
}
