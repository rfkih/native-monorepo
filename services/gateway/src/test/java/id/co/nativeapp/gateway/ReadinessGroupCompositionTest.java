package id.co.nativeapp.gateway;

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
 * Container-free positive proof of the gateway's readiness health GROUP composition
 * (ENGINEERING-STANDARDS §7). The gateway is STATELESS — no database, no Kafka consumer, no read
 * model to hydrate — so its readiness gates ONLY on the {@code readinessState} lifecycle indicator
 * and must reference NEITHER {@code db} NOR {@code kafka} (there is no such contributor; naming one
 * would fail the group-membership validator at startup, and gating readiness on Redis — a
 * degradable convenience — would wrongly pull the gateway from rotation on a cache blip).
 *
 * <p>Binds the ACTUAL {@code application.yml} the gateway ships, the same property Spring Boot
 * binds at runtime, instead of booting the edge — proving the membership without a container.
 */
class ReadinessGroupCompositionTest {

  @Test
  void readinessGroupIsReadinessStateOnlyWithNoDbOrKafka() {
    List<String> readinessInclude = readinessGroupInclude();

    assertThat(readinessInclude)
        .as("the stateless gateway gates readiness only on the lifecycle state")
        .containsExactly("readinessState");
    assertThat(readinessInclude).doesNotContain("db", "kafka");
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
