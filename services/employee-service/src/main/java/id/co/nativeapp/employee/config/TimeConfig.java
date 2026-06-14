package id.co.nativeapp.employee.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the {@link Clock} the writers use to stamp the {@code occurred_at} of the {@code
 * EmployeeChanged} / {@code AssignmentChanged} outbox rows. Injecting a {@code Clock} — rather than
 * calling {@code Instant.now()} directly — keeps the time source explicit and lets a test pin "now"
 * deterministically. The default is the system UTC clock (all Native timestamps are UTC).
 */
@Configuration
public class TimeConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
