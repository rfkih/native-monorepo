package id.co.nativeapp.errorinbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** ErrorInboxWriter must never throw into the DLT path, and must redact PII before storage. */
class ErrorInboxWriterFailSafeTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);
  private final ErrorInboxWriter writer =
      new ErrorInboxWriter(new ErrorMessageRedactor(), jdbc, tx, clock);

  @Test
  void swallowsAFailedUpsertAndReturnsTheFailedSentinel() {
    doThrow(new RuntimeException("db unreachable")).when(tx).execute(any());
    ErrorInboxWriter.Recorded r =
        writer.record(new IllegalStateException("boom"), "kafka:X", null, null);
    assertThat(r.occurrenceCount()).isZero();
    assertThat(r.fingerprint()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void returnsTheRedactedMessageAndCountOnSuccess() {
    // Drive the REAL transaction callback (not a canned template return) so the upsert lambda body
    // executes — the jdbc layer below it is stubbed to the post-upsert count.
    doReturn(7L)
        .when(jdbc)
        .queryForObject(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(Long.class),
            any(Object[].class));
    org.mockito.Mockito.when(tx.execute(any()))
        .thenAnswer(
            inv ->
                ((org.springframework.transaction.support.TransactionCallback<Long>)
                        inv.getArgument(0))
                    .doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
    ErrorInboxWriter.Recorded r =
        writer.record(
            new IllegalStateException("fail for user@x.com nik 3201234567890123"),
            "kafka:SaleRecorded",
            "company-1",
            "trace-1");
    assertThat(r.occurrenceCount()).isEqualTo(7L);
    assertThat(r.redactedMessage()).contains("***@***").doesNotContain("3201234567890123");
    assertThat(r.fingerprint()).isNotBlank();
  }

  @Test
  void recordInCurrentTxUpsertsDirectlyWithoutTheRequiresNewTemplate() {
    doReturn(3L)
        .when(jdbc)
        .queryForObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class));
    ErrorInboxWriter.Recorded r =
        writer.recordInCurrentTx(
            new IllegalStateException("oversold stock for user@x.com"),
            "restaurant.stock.offline-discrepancy",
            "company-1",
            null);
    assertThat(r.occurrenceCount()).isEqualTo(3L);
    assertThat(r.redactedMessage()).contains("***@***");
    // The caller's transaction is joined — the REQUIRES_NEW template must never be touched.
    org.mockito.Mockito.verifyNoInteractions(tx);
  }

  @Test
  void recordInCurrentTxSwallowsAFailedUpsertAndReturnsTheFailedSentinel() {
    doThrow(new RuntimeException("db unreachable"))
        .when(jdbc)
        .queryForObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class));
    ErrorInboxWriter.Recorded r =
        writer.recordInCurrentTx(new IllegalStateException("boom"), "src", null, null);
    assertThat(r.occurrenceCount()).isZero();
    assertThat(r.fingerprint()).isNull();
  }
}
