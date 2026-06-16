package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Container-free positive proof of finance-service's readiness health GROUP composition
 * (ENGINEERING-STANDARDS §7). finance-service is a Kafka CONSUMER (it builds its ledger read model
 * from {@code SaleRecorded}), so its readiness must aggregate BOTH the {@code db} and the {@code
 * kafka} contributors — a missing {@code kafka} here would let the pod take traffic while its read
 * model is still hydrating (or the broker is down). It must also include {@code readinessState}
 * (the lifecycle indicator).
 *
 * <p>This binds the ACTUAL {@code application.yml} the service ships (the same property, {@code
 * management.endpoint.health.group.readiness.include}, Spring Boot binds at runtime) rather than
 * booting a context with real PostgreSQL/Kafka — proving the membership without standing up
 * containers, exactly as the hardening task prefers.
 */
class ReadinessGroupCompositionTest {

  @Test
  void readinessGroupAggregatesReadinessStateDbAndKafka() {
    List<String> readinessInclude = readinessGroupInclude();

    assertThat(readinessInclude)
        .as("finance-service is a Kafka consumer: readiness must gate on db AND kafka")
        .contains("readinessState", "db", "kafka");
  }

  /**
   * Loads {@code management.endpoint.health.group.readiness.include} straight from the service's
   * packaged {@code application.yml} (the FIRST YAML document — the management block lives there,
   * not in the {@code !dev} security document), using the same Spring Boot YAML loader + relaxed
   * binder that bind it at runtime.
   */
  private static List<String> readinessGroupInclude() {
    try {
      List<PropertySource<?>> sources =
          new YamlPropertySourceLoader()
              .load("application.yml", new ClassPathResource("application.yml"));
      // The management config is in the first document; bind over it.
      Binder binder = new Binder(ConfigurationPropertySources.from(sources.get(0)));
      return binder
          .bind("management.endpoint.health.group.readiness.include", Bindable.listOf(String.class))
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "readiness group 'include' missing from application.yml"));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("could not read application.yml", e);
    }
  }
}
