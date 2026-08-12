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
 * → ADR 0009 fleet rollout) on every service that puts {@code libs/error-inbox} on its classpath.
 * Registered in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <p><strong>Two activation tiers (ADR 0040 gap closure).</strong> The class itself activates on a
 * JDBC stack alone ({@link JdbcTemplate}), so the <em>write path</em> — {@link ErrorInboxWriter}
 * and its {@link ErrorMessageRedactor}, {@link Clock}, and {@code REQUIRES_NEW} {@link
 * TransactionTemplate} collaborators — is available on ANY service with a database, whether or not
 * it consumes Kafka. That is what lets the shared {@code ApiExceptionHandler} persist an HTTP 500
 * to {@code error_log} on a pure-producer service such as org-service (no {@code kafka-clients} on
 * its classpath — {@code compileOnly} in this lib — so {@link ConsumerRecord} is absent at
 * runtime). The <em>consumer/alert path</em> — {@link AlertWebhookClient} and {@link
 * ConsumeErrorRecorder}, which exist to record and alert on a DLT'd event — is gated per-bean on
 * {@link ConsumerRecord}, so it activates only on the event-CONSUMING services that actually have a
 * DLT recoverer to wrap. A pure-producer service gets the writer but not the recorder.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean} so a service can override any piece (e.g.
 * finance and org-service each keep their own {@link Clock} bean; this lib's {@code
 * errorInboxClock} then yields to it).
 *
 * <p>The {@code error_log} TABLE is NOT provided here — it is each service's own Flyway migration
 * (a per-service database; an ops table, NOT {@code Auditable}, NOT RLS — see the migration header
 * + ADR 0005). A service activates the write path by (a) depending on this lib (directly, or
 * transitively via {@code libs/security}), and (b) adding the {@code error_log} migration; the
 * consumer path additionally needs (c) wrapping its DLT recoverer with {@link
 * ConsumeErrorRecorder}.
 */
@AutoConfiguration
@ConditionalOnClass(JdbcTemplate.class)
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

  /**
   * The webhook egress used exclusively by {@link ConsumeErrorRecorder}, so it is gated on the same
   * Kafka classpath — a pure-producer service (no DLT to alert on) needs neither.
   */
  @Bean(destroyMethod = "shutdown")
  @ConditionalOnClass(ConsumerRecord.class)
  @ConditionalOnMissingBean
  public AlertWebhookClient alertWebhookClient(
      @Value("${native.alert.webhook-url:}") String webhookUrl,
      @Value("${native.alert.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${native.alert.read-timeout-ms:3000}") int readTimeoutMs) {
    return new AlertWebhookClient(webhookUrl, connectTimeoutMs, readTimeoutMs);
  }

  /**
   * The DLT-consume recorder, present only on event-CONSUMING services — it records a DLT'd {@link
   * ConsumerRecord}, so it is gated on Kafka being on the classpath. The write path above ({@link
   * ErrorInboxWriter}) stays available JDBC-only for the HTTP-500 persistence path (ADR 0040).
   */
  @Bean
  @ConditionalOnClass(ConsumerRecord.class)
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
