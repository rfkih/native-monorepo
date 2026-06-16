package id.co.nativeapp.org;

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
 * Container-free positive proof of org-service's readiness health GROUP composition
 * (ENGINEERING-STANDARDS §7). org-service is a DB-backed service with NO Kafka consumer, so its
 * readiness must aggregate {@code readinessState} + {@code db} — and must NOT reference {@code
 * kafka} (there is no Kafka contributor to gate on; naming it would fail the group-membership
 * validator at startup).
 *
 * <p>Binds the ACTUAL {@code application.yml} the service ships, the same property Spring Boot
 * binds at runtime, instead of booting a context with a real PostgreSQL — proving the membership
 * without a container.
 */
class ReadinessGroupCompositionTest {

  @Test
  void readinessGroupAggregatesReadinessStateAndDbButNotKafka() {
    List<String> readinessInclude = readinessGroupInclude();

    assertThat(readinessInclude)
        .as("org-service is DB-backed with no Kafka consumer")
        .contains("readinessState", "db")
        .doesNotContain("kafka");
  }

  private static List<String> readinessGroupInclude() {
    try {
      List<PropertySource<?>> sources =
          new YamlPropertySourceLoader()
              .load("application.yml", new ClassPathResource("application.yml"));
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
