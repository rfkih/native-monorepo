package id.co.nativeapp.errorinbox;

import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Auto-configuration that registers the shared error-inbox + webhook-alerting beans (ADR 0005 pilot
 * → ADR 0009 fleet rollout) on every event-CONSUMING service that puts {@code libs/error-inbox} on
 * its classpath. Registered in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <p>Active only when both a JDBC stack ({@link JdbcTemplate}) and Kafka ({@link ConsumerRecord})
 * are present, so a hypothetical DB-only / non-Kafka module never activates it. Every bean is
 * {@code @ConditionalOnMissingBean} so a service can override any piece (e.g. finance keeps its own
 * {@link Clock} bean; this lib's {@code errorInboxClock} then yields to it).
 *
 * <p>The {@code error_log} TABLE is NOT provided here — it is each service's own Flyway migration
 * (a per-service database; an ops table, NOT {@code Auditable}, NOT RLS — see the migration header
 * + ADR 0005). A service activates the inbox by (a) depending on this lib, (b) adding the {@code
 * error_log} migration, and (c) wrapping its DLT recoverer with {@link ConsumeErrorRecorder}.
 */
@AutoConfiguration
@ConditionalOnClass({JdbcTemplate.class, ConsumerRecord.class})
public class ErrorInboxAutoConfiguration {

  /**
   * Bounded timeout (seconds) for the error-inbox upsert — see {@link
   * #errorInboxTransactionTemplate}.
   */
  private static final int ERROR_INBOX_TX_TIMEOUT_SECONDS = 5;

  @Bean
  @ConditionalOnMissingBean
  public ErrorMessageRedactor errorMessageRedactor() {
    return new ErrorMessageRedactor();
  }

  /** A system UTC clock unless the service already declares its own {@link Clock} bean. */
  @Bean
  @ConditionalOnMissingBean
  public Clock errorInboxClock() {
    return Clock.systemUTC();
  }

  /**
   * A {@link TransactionTemplate} that always runs in a brand-new transaction ({@code
   * PROPAGATION_REQUIRES_NEW}), used exclusively by {@link ErrorInboxWriter} to persist error
   * records even when the surrounding business transaction has already been marked for rollback. A
   * bounded {@linkplain #ERROR_INBOX_TX_TIMEOUT_SECONDS timeout} keeps a slow upsert from stalling
   * the consumer partition just before the DLT publish (ADR 0005).
   */
  @Bean
  @ConditionalOnMissingBean(name = "errorInboxTransactionTemplate")
  public TransactionTemplate errorInboxTransactionTemplate(PlatformTransactionManager txManager) {
    TransactionTemplate template = new TransactionTemplate(txManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setTimeout(ERROR_INBOX_TX_TIMEOUT_SECONDS);
    return template;
  }

  @Bean
  @ConditionalOnMissingBean
  public ErrorInboxWriter errorInboxWriter(
      ErrorMessageRedactor redactor,
      JdbcTemplate jdbcTemplate,
      TransactionTemplate errorInboxTransactionTemplate,
      Clock errorInboxClock) {
    return new ErrorInboxWriter(
        redactor, jdbcTemplate, errorInboxTransactionTemplate, errorInboxClock);
  }

  @Bean(destroyMethod = "shutdown")
  @ConditionalOnMissingBean
  public AlertWebhookClient alertWebhookClient(
      @Value("${native.alert.webhook-url:}") String webhookUrl,
      @Value("${native.alert.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${native.alert.read-timeout-ms:3000}") int readTimeoutMs) {
    return new AlertWebhookClient(webhookUrl, connectTimeoutMs, readTimeoutMs);
  }

  @Bean
  @ConditionalOnMissingBean
  public ConsumeErrorRecorder consumeErrorRecorder(
      ErrorInboxWriter errorInboxWriter,
      AlertWebhookClient alertWebhookClient,
      Clock errorInboxClock,
      @Value("${spring.application.name:unknown-service}") String serviceName) {
    return new ConsumeErrorRecorder(
        errorInboxWriter, alertWebhookClient, errorInboxClock, serviceName);
  }
}
