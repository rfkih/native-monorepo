package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Guard (representative): a booted Native service must NEVER emit {@code service=unknown-service} —
 * that string is the {@code defaultValue} the shared {@code logback-native-json.xml} falls back to
 * when {@code spring.application.name} is unset, and an unset name would also break the per-service
 * {@code service} metric tag and log routing. Proving the name is set keeps the fallback dead code.
 *
 * <p>Binds the service's packaged {@code application.yml} (the same property Spring Boot binds at
 * runtime) and asserts {@code spring.application.name} is present, non-blank, and not the {@code
 * unknown-service} fallback — no boot, no container.
 */
class ApplicationNameIsSetTest {

  /**
   * The logback fallback in libs/observability/logback-native-json.xml — must never be the name.
   */
  private static final String FALLBACK = "unknown-service";

  @Test
  void springApplicationNameIsSetAndNotTheUnknownServiceFallback() {
    String name = applicationName();

    assertThat(name)
        .as("spring.application.name must be set so logs never emit service=" + FALLBACK)
        .isNotBlank()
        .isNotEqualTo(FALLBACK)
        .isEqualTo("finance-service");
  }

  private static String applicationName() {
    try {
      List<PropertySource<?>> sources =
          new YamlPropertySourceLoader()
              .load("application.yml", new ClassPathResource("application.yml"));
      Binder binder = new Binder(ConfigurationPropertySources.from(sources.get(0)));
      return binder
          .bind("spring.application.name", String.class)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "spring.application.name missing from application.yml"));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("could not read application.yml", e);
    }
  }
}
