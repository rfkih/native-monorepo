package id.co.nativeapp.finance.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.finance.observability.dto.AlertPayload;
import id.co.nativeapp.finance.observability.service.AlertWebhookClient;
import id.co.nativeapp.finance.observability.service.ConsumeErrorRecorder;
import id.co.nativeapp.finance.observability.service.ErrorInboxWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ConsumeErrorRecorder} using Mockito.
 *
 * <p>Verified behaviours:
 *
 * <ul>
 *   <li>{@code occurrenceCount == 1} → {@link AlertWebhookClient#send} invoked once.
 *   <li>{@code occurrenceCount == 2} → alert NOT invoked (below any milestone).
 *   <li>{@code occurrenceCount == 100} → alert invoked (milestone).
 *   <li>{@code occurrenceCount == 2000} → alert invoked (1000-multiple milestone).
 *   <li>Writer throws → recorder does not throw (ops infrastructure is fail-safe).
 *   <li><strong>The alert payload carries the REDACTED message and the REAL fingerprint</strong>
 *       returned by the writer — never the raw exception text (HR-6 / ADR 0005 egress guard) — and
 *       a {@code lastSeen} stamped from the injected {@link Clock}.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ConsumeErrorRecorderTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-06-20T12:00:00Z");

  @Mock private ErrorInboxWriter errorInboxWriter;
  @Mock private AlertWebhookClient alertWebhookClient;

  private ConsumeErrorRecorder recorder;

  @BeforeEach
  void setUp() {
    recorder =
        new ConsumeErrorRecorder(
            errorInboxWriter, alertWebhookClient, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
  }

  private ConsumerRecord<String, byte[]> record(String topic) {
    return new ConsumerRecord<>(topic, 0, 0L, "key", new byte[0]);
  }

  /**
   * A recorded result with the given occurrence count, a stable fingerprint, and a redacted msg.
   */
  private static ErrorInboxWriter.Recorded recorded(long count) {
    return new ErrorInboxWriter.Recorded(count, "fp-" + count, "redacted");
  }

  @Test
  void occurrenceCount1TriggersAlert() {
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull())).thenReturn(recorded(1L));

    recorder.record(record("SaleRecorded"), new RuntimeException("decode error"));

    verify(alertWebhookClient).send(any(AlertPayload.class));
  }

  @Test
  void occurrenceCount2DoesNotTriggerAlert() {
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull())).thenReturn(recorded(2L));

    recorder.record(record("SaleRecorded"), new RuntimeException("decode error"));

    verify(alertWebhookClient, never()).send(any());
  }

  @Test
  void occurrenceCount10TriggersAlert() {
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull())).thenReturn(recorded(10L));

    recorder.record(record("ExpenseRecorded"), new RuntimeException("avro error"));

    verify(alertWebhookClient).send(any(AlertPayload.class));
  }

  @Test
  void occurrenceCount100TriggersAlert() {
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull()))
        .thenReturn(recorded(100L));

    recorder.record(record("LaborCostAllocated"), new RuntimeException("timeout"));

    verify(alertWebhookClient).send(any(AlertPayload.class));
  }

  @Test
  void occurrenceCount2000TriggersAlert() {
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull()))
        .thenReturn(recorded(2000L));

    recorder.record(record("PayrollPosted"), new RuntimeException("schema mismatch"));

    verify(alertWebhookClient).send(any(AlertPayload.class));
  }

  @Test
  void occurrenceCount999DoesNotTriggerAlert() {
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull()))
        .thenReturn(recorded(999L));

    recorder.record(record("SaleRecorded"), new RuntimeException("error"));

    verify(alertWebhookClient, never()).send(any());
  }

  @Test
  void writerThrowingDoesNotThrowFromRecorder() {
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull()))
        .thenThrow(new RuntimeException("DB unreachable"));

    assertThatCode(
            () -> recorder.record(record("SaleRecorded"), new RuntimeException("decode error")))
        .doesNotThrowAnyException();

    verify(alertWebhookClient, never()).send(any());
  }

  @Test
  void sourceIsDerivedFromTopicName() {
    when(errorInboxWriter.record(any(), eq("kafka:SaleRecorded"), isNull(), isNull()))
        .thenReturn(recorded(0L));

    recorder.record(record("SaleRecorded"), new RuntimeException("error"));

    verify(errorInboxWriter).record(any(), eq("kafka:SaleRecorded"), isNull(), isNull());
  }

  // ----------------------------------------------------- PII egress guard (B1 / HR-6)

  @Test
  void alertPayloadCarriesRedactedMessageAndRealFingerprintNeverRawMessage() {
    // The writer returns the REDACTED message + the real dedup fingerprint; the recorder must put
    // those on the wire, NOT the raw cause.getMessage() (which here contains a NIK).
    ErrorInboxWriter.Recorded rec =
        new ErrorInboxWriter.Recorded(1L, "sha256-row-key", "Employee NIK *** failed validation");
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull())).thenReturn(rec);
    ArgumentCaptor<AlertPayload> captor = ArgumentCaptor.forClass(AlertPayload.class);

    recorder.record(
        record("PayrollPosted"),
        new RuntimeException("Employee NIK 3201234567890001 failed validation"));

    verify(alertWebhookClient).send(captor.capture());
    AlertPayload sent = captor.getValue();
    assertThat(sent.message()).isEqualTo("Employee NIK *** failed validation");
    assertThat(sent.message()).doesNotContain("3201234567890001");
    assertThat(sent.fingerprint()).isEqualTo("sha256-row-key");
    assertThat(sent.lastSeen()).isEqualTo(FIXED_NOW);
  }
}
