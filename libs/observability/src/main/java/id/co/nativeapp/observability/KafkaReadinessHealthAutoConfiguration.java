package id.co.nativeapp.observability;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Registers the shared {@code kafka} readiness health indicator (ENGINEERING-STANDARDS §7) for
 * every Native Kafka <em>consumer</em> service, with NO copied code — a service gets it simply by
 * being on the classpath of {@code libs/observability} (which it already is, for the shared JSON
 * logging) AND pulling in spring-kafka + actuator (which the consumer services do).
 *
 * <p>It exists because Spring Boot 4 ships no built-in Kafka health contributor, so a service that
 * lists {@code kafka} in its readiness group would otherwise fail startup on the health-group
 * membership validator. The conditions keep it inert everywhere else:
 *
 * <ul>
 *   <li>{@link ConditionalOnClass} {@link KafkaAdmin}/{@link AdminClient}/{@link HealthIndicator} —
 *       a DB-only service (org/restaurant) or the stateless gateway, which depend on this lib only
 *       for logging, never see the Kafka/actuator types, so the auto-config is skipped entirely;
 *   <li>{@link ConditionalOnBean} {@link KafkaAdmin} — only a service whose Kafka
 *       auto-configuration actually stood up an admin gets the indicator;
 *   <li>{@link ConditionalOnEnabledHealthIndicator} {@code kafka} — respects {@code
 *       management.health.kafka.enabled=false};
 *   <li>{@link ConditionalOnMissingBean} — a service may still supply its own {@code
 *       kafkaHealthIndicator} and this steps aside.
 * </ul>
 *
 * <p>The bean is named {@code kafkaHealthIndicator} so Spring registers it as the health
 * contributor {@code kafka} (the {@code HealthIndicator} suffix is stripped) — exactly the name the
 * readiness group references.
 *
 * <p>{@code afterName} pins this to run AFTER Spring Boot's {@code KafkaAutoConfiguration}
 * (referenced by name to avoid a hard load-time coupling), because that is what registers the
 * {@code KafkaAdmin} bean the {@link ConditionalOnBean} below looks for — otherwise this evaluates
 * first, finds no admin, and the {@code kafka} contributor is silently never created (the readiness
 * group then fails the membership validator at startup).
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass({KafkaAdmin.class, AdminClient.class, HealthIndicator.class})
public class KafkaReadinessHealthAutoConfiguration {

  /** A bounded describe-cluster timeout (ms): a readiness probe must fail fast, not hang. */
  static final int DEFAULT_REQUEST_TIMEOUT_MS = 2000;

  @Bean
  @ConditionalOnBean(KafkaAdmin.class)
  @ConditionalOnEnabledHealthIndicator("kafka")
  @ConditionalOnMissingBean(name = "kafkaHealthIndicator")
  public KafkaReadinessHealthIndicator kafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
    return new KafkaReadinessHealthIndicator(kafkaAdmin, DEFAULT_REQUEST_TIMEOUT_MS);
  }
}
