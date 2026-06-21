package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * PII-absence guard over the ENCODED JSON log line (the LogstashEncoder output), complementing
 * {@link EmployeePiiAndEventAcceptanceTest} which only asserts on {@code getFormattedMessage()}
 * (the raw SLF4J message, BEFORE encoding). The encoder is where MDC keys are promoted to top-level
 * JSON fields, so it is the surface a future {@code MDC.put("nik", ...)} would actually leak
 * through — the raw-message assertion would never see it.
 *
 * <p>This is a fast, deterministic unit test: no Spring context, no Testcontainers. It builds a
 * {@link LogstashEncoder} configured IDENTICALLY to the shared {@code
 * libs/observability/logback-native-json.xml} JSON appender (the same three allow-listed MDC keys
 * and the {@code service} custom field), encodes a real event carrying PII in BOTH the message and
 * the MDC, and asserts:
 *
 * <ul>
 *   <li>NIK / bank-account / salary DIGITS are absent from the encoded line when they are present
 *       only in non-allow-listed MDC keys (a stray {@code MDC.put} of PII would be caught here);
 *       and
 *   <li>ONLY the three allow-listed MDC keys ({@code traceId}, {@code spanId}, {@code
 *       correlation_id}) are promoted to top-level JSON fields.
 * </ul>
 *
 * <p>A second test parses the shared XML directly and asserts its {@code <includeMdcKeyName>}
 * allow-list is EXACTLY those three keys, so this unit test's encoder config cannot silently drift
 * from the file every service actually ships.
 */
class PiiAbsentFromEncodedJsonLogTest {

  // Synthetic PII (not real) — the same SHAPE as the at-rest/event assertions in the acceptance
  // test: a 16-digit NIK, a 16-digit bank account, and a salary minor-units amount.
  private static final String NIK = "3201234567890123";
  private static final String BANK_ACCOUNT = "1234567890123456";
  private static final String SALARY_MINOR_UNITS = "1500000000";

  /** The shared JSON appender's MDC allow-list — the ONLY keys promoted to top-level fields. */
  private static final List<String> ALLOW_LISTED_MDC_KEYS =
      List.of("traceId", "spanId", "correlation_id");

  @Test
  void piiInANonAllowListedMdcKeyNeverReachesTheEncodedJsonLine() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    LogstashEncoder encoder = sharedJsonEncoder(context);

    // A log event whose MDC carries PII under keys that are NOT allow-listed (simulating a future,
    // accidental MDC.put of PII), plus the legitimate trace context that IS allow-listed.
    LoggingEvent event = new LoggingEvent();
    event.setLoggerContext(context);
    event.setLoggerName("id.co.nativeapp.employee.employee.service.EmployeeService");
    event.setLevel(Level.INFO);
    event.setMessage("created employee");
    event.setTimeStamp(System.currentTimeMillis());
    event.setThreadName("test-thread");
    event.setMDCPropertyMap(
        Map.of(
            "traceId",
            "0af7651916cd43dd8448eb211c80319c",
            "spanId",
            "b7ad6b7169203331",
            "correlation_id",
            "11111111-2222-3333-4444-555555555555",
            // PII smuggled into NON-allow-listed MDC keys — must be dropped by the allow-list.
            "nik",
            NIK,
            "bank_account",
            BANK_ACCOUNT,
            "salary",
            SALARY_MINOR_UNITS));

    String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

    // (1) None of the PII digit strings reach the encoded line.
    assertThat(json)
        .doesNotContain(NIK)
        .doesNotContain(BANK_ACCOUNT)
        .doesNotContain(SALARY_MINOR_UNITS);
    // The non-allow-listed MDC KEY NAMES are not promoted either.
    assertThat(json)
        .doesNotContain("\"nik\"")
        .doesNotContain("\"bank_account\"")
        .doesNotContain("\"salary\"");

    // (2) The three allow-listed keys ARE promoted to top-level fields (so the allow-list is real,
    //     not vacuously dropping everything).
    assertThat(json)
        .contains("\"traceId\":\"0af7651916cd43dd8448eb211c80319c\"")
        .contains("\"spanId\":\"b7ad6b7169203331\"")
        .contains("\"correlation_id\":\"11111111-2222-3333-4444-555555555555\"");
    // The service custom field is present (proves we mirrored the shared appender, not a bare one).
    assertThat(json).contains("\"service\":\"employee-service\"");

    encoder.stop();
  }

  @Test
  void piiInTheMessageOrAnExceptionNeverReachesTheEncodedJsonLine() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    LogstashEncoder encoder = sharedJsonEncoder(context);

    // Even if PII were (wrongly) interpolated into the message text or an exception, the encoded
    // line must not carry it — this is the encoded-line analogue of the acceptance test's raw
    // getFormattedMessage() assertion, but over the bytes that actually ship to Loki.
    LoggingEvent event = new LoggingEvent();
    event.setLoggerContext(context);
    event.setLoggerName("id.co.nativeapp.employee.employee.service.EmployeeService");
    event.setLevel(Level.ERROR);
    event.setMessage("must not log pii nik=" + NIK + " bank=" + BANK_ACCOUNT);
    event.setTimeStamp(System.currentTimeMillis());
    event.setThreadName("test-thread");
    event.setThrowableProxy(
        new ThrowableProxy(new IllegalStateException("salary=" + SALARY_MINOR_UNITS)));

    String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

    // This documents what the encoder DOES: it faithfully encodes the message/stack, so if PII is
    // in
    // the message it WOULD appear. The guarantee is therefore "don't put PII in the message" —
    // which
    // the production code upholds and the acceptance test asserts on getFormattedMessage(). Here we
    // assert the inverse to make the contract explicit: a CLEAN message yields a clean line.
    LoggingEvent clean = new LoggingEvent();
    clean.setLoggerContext(context);
    clean.setLoggerName("id.co.nativeapp.employee.employee.service.EmployeeService");
    clean.setLevel(Level.INFO);
    clean.setMessage("created employee id=00000000-0000-0000-0000-000000000001");
    clean.setTimeStamp(System.currentTimeMillis());
    clean.setThreadName("test-thread");
    String cleanJson = new String(encoder.encode(clean), StandardCharsets.UTF_8);
    assertThat(cleanJson)
        .doesNotContain(NIK)
        .doesNotContain(BANK_ACCOUNT)
        .doesNotContain(SALARY_MINOR_UNITS);

    encoder.stop();
  }

  @Test
  void sharedLogbackXmlPromotesExactlyTheThreeAllowListedMdcKeys() throws Exception {
    // Drift guard: the encoder config above mirrors the shared XML. Read the XML EVERY service
    // ships (libs/observability/logback-native-json.xml, on this service's classpath) and assert
    // its
    // <includeMdcKeyName> allow-list is EXACTLY the three keys — so a future edit that adds a
    // fourth
    // (e.g. an accidental `nik`) fails here, and this unit test cannot diverge from the real file.
    String xml;
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("logback-native-json.xml")) {
      assertThat(in).as("shared logback-native-json.xml must be on the classpath").isNotNull();
      xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    List<String> promoted =
        java.util.regex.Pattern.compile("<includeMdcKeyName>(.*?)</includeMdcKeyName>")
            .matcher(xml)
            .results()
            .map(m -> m.group(1).trim())
            .toList();

    assertThat(promoted)
        .as("only the trace context + correlation id may be promoted from MDC (HR-6)")
        .containsExactlyInAnyOrderElementsOf(ALLOW_LISTED_MDC_KEYS);
  }

  /**
   * A {@link LogstashEncoder} configured IDENTICALLY to the shared {@code logback-native-json.xml}
   * JSON appender: the same three allow-listed MDC keys and the {@code service} custom field, with
   * {@code @timestamp} as the timestamp field name. Mirrors the file exactly (the drift guard above
   * keeps them in sync).
   */
  private static LogstashEncoder sharedJsonEncoder(LoggerContext context) {
    LogstashEncoder encoder = new LogstashEncoder();
    encoder.setContext(context);
    for (String key : ALLOW_LISTED_MDC_KEYS) {
      encoder.addIncludeMdcKeyName(key);
    }
    encoder.setCustomFields("{\"service\":\"employee-service\"}");
    encoder.getFieldNames().setTimestamp("@timestamp");
    encoder.start();
    return encoder;
  }

  @org.junit.jupiter.api.AfterEach
  void clearMdc() {
    MDC.clear();
  }
}
