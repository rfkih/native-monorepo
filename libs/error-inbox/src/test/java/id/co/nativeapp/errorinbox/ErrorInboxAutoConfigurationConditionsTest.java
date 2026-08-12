package id.co.nativeapp.errorinbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The two-tier activation contract of {@link ErrorInboxAutoConfiguration} (ADR 0040 gap closure).
 *
 * <p>The <em>write path</em> ({@link ErrorInboxWriter} + its redactor / clock / REQUIRES_NEW
 * transaction template) must come up on a JDBC-only classpath — with NO {@code kafka-clients} — so
 * a pure-producer service such as org-service can persist an HTTP 500 to {@code error_log} even
 * though it runs no Kafka consumer. The <em>consumer/alert path</em> ({@link AlertWebhookClient} +
 * {@link ConsumeErrorRecorder}) must stay gated on {@link ConsumerRecord}, so it appears only where
 * there is a DLT recoverer to wrap. Before the split both tiers were gated together and the writer
 * never activated without Kafka on the classpath.
 */
class ErrorInboxAutoConfigurationConditionsTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ErrorInboxAutoConfiguration.class))
          .withUserConfiguration(StubInfra.class);

  @Test
  void writePathActivatesJdbcOnly_whenKafkaIsAbsentFromTheClasspath() {
    runner
        .withClassLoader(new FilteredClassLoader(ConsumerRecord.class))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              // Write path — available on a database alone (the org-service / HTTP-500 case).
              assertThat(ctx).hasSingleBean(ErrorInboxWriter.class);
              assertThat(ctx).hasSingleBean(ErrorMessageRedactor.class);
              // Consumer/alert path — must NOT come up without a Kafka classpath.
              assertThat(ctx).doesNotHaveBean(ConsumeErrorRecorder.class);
              assertThat(ctx).doesNotHaveBean(AlertWebhookClient.class);
            });
  }

  @Test
  void errorInboxClockYieldsToAServiceOwnedClock_theOrgServiceCase() {
    // org-service already declares its own Clock (config/TimeConfig); errorInboxClock is
    // @ConditionalOnMissingBean, so the context must end with a SINGLE Clock — the service's — and
    // the write path must still come up around it. This is the exact wiring the full-context boot
    // relies on; pin it here so a regression is caught without Docker.
    Clock serviceClock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC);
    runner
        .withClassLoader(new FilteredClassLoader(ConsumerRecord.class))
        .withBean("serviceClock", Clock.class, () -> serviceClock)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(Clock.class);
              assertThat(ctx.getBean(Clock.class)).isSameAs(serviceClock);
              assertThat(ctx).hasSingleBean(ErrorInboxWriter.class);
            });
  }

  @Test
  void bothPathsActivate_whenKafkaIsOnTheClasspath() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx).hasSingleBean(ErrorInboxWriter.class);
          assertThat(ctx).hasSingleBean(ConsumeErrorRecorder.class);
          assertThat(ctx).hasSingleBean(AlertWebhookClient.class);
        });
  }

  /**
   * The minimal collaborators the auto-config needs from the host service: a {@link JdbcTemplate}
   * (the error_log upsert target) and a {@link PlatformTransactionManager} (behind the REQUIRES_NEW
   * template). Both are mocked — no bean here is exercised, only wired.
   */
  @Configuration(proxyBeanMethods = false)
  static class StubInfra {

    @Bean
    JdbcTemplate jdbcTemplate() {
      return mock(JdbcTemplate.class);
    }

    @Bean
    PlatformTransactionManager transactionManager() {
      return mock(PlatformTransactionManager.class);
    }
  }
}
