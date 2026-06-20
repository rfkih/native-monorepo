package id.co.nativeapp.finance.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import id.co.nativeapp.finance.observability.service.ErrorInboxWriter;
import id.co.nativeapp.finance.observability.service.ErrorMessageRedactor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fail-safe unit tests for {@link ErrorInboxWriter}.
 *
 * <p>Recording an error MUST NEVER throw and MUST degrade to {@link
 * ErrorInboxWriter.Recorded#failed()} (count {@code 0} → suppresses any alert) when the upsert
 * fails — so an ops-infrastructure fault (DB down, pool starved) can never break the DLT-publish
 * path that runs right after it. The happy-path upsert against real SQL (dedup, redaction storage)
 * is covered by the Testcontainers {@code ErrorInboxWriterTest}; here we drive the failure branches
 * with mocks, which the container test cannot easily force.
 */
@ExtendWith(MockitoExtension.class)
class ErrorInboxWriterFailSafeTest {

  @Mock private ErrorMessageRedactor redactor;
  @Mock private JdbcTemplate jdbc;
  @Mock private TransactionTemplate requiresNew;

  private ErrorInboxWriter writer;

  @BeforeEach
  void setUp() {
    writer =
        new ErrorInboxWriter(
            redactor,
            jdbc,
            requiresNew,
            Clock.fixed(Instant.parse("2026-06-20T00:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void upsertThrowing_returnsFailedSentinel_andDoesNotPropagate() {
    when(redactor.redact(anyString())).thenReturn("redacted");
    when(requiresNew.execute(any())).thenThrow(new RuntimeException("DB unreachable"));

    // If record() propagated the failure this call would error the test — that IS the fail-safe
    // proof; the assertions then pin the degraded sentinel that suppresses alerting.
    ErrorInboxWriter.Recorded result =
        writer.record(new RuntimeException("boom"), "kafka:SaleRecorded", null, null);

    assertThat(result.occurrenceCount()).isZero();
    assertThat(result.fingerprint()).isNull();
    assertThat(result.redactedMessage()).isEmpty();
  }

  @Test
  void upsertReturningNullCount_yieldsZeroOccurrences_keepingFingerprintAndRedactedMessage() {
    when(redactor.redact(anyString())).thenReturn("redacted msg");
    when(redactor.fingerprintNormalize(anyString())).thenReturn("normalized");
    when(requiresNew.execute(any())).thenReturn(null);

    ErrorInboxWriter.Recorded result =
        writer.record(new RuntimeException("boom"), "kafka:SaleRecorded", null, null);

    // A null count must not alert (0), but the row context is still populated.
    assertThat(result.occurrenceCount()).isZero();
    assertThat(result.fingerprint()).isNotBlank();
    assertThat(result.redactedMessage()).isEqualTo("redacted msg");
  }
}
