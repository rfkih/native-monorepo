package id.co.nativeapp.org.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the {@link Clock} the writers use to effective-date new org-unit rows (and to close a
 * row on deactivation). Injecting a {@code Clock} — rather than calling {@code LocalDate.now()}
 * directly — keeps the time source explicit and lets a test pin "today" deterministically if it
 * needs to. The default is the system UTC clock (all Native timestamps are UTC).
 */
@Configuration
public class TimeConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
