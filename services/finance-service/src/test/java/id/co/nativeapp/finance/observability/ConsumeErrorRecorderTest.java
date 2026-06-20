package id.co.nativeapp.finance.observability;

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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ConsumeErrorRecorderTest {

  @Mock private ErrorInboxWriter errorInboxWriter;
  @Mock private AlertWebhookClient alertWebhookClient;

  @InjectMocks private ConsumeErrorRecorder recorder;

  private ConsumerRecord<String, byte[]> record(String topic) {
    return new ConsumerRecord<>(topic, 0, 0L, "key", new byte[0]);
  }

  @Test
  void occurrenceCount1TriggersAlert() {
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull())).thenReturn(1L);

    recorder.record(record("SaleRecorded"), new RuntimeException("decode error"));

    verify(alertWebhookClient).send(any(AlertPayload.class));
  }

  @Test
  void occurrenceCount2DoesNotTriggerAlert() {
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull())).thenReturn(2L);

    recorder.record(record("SaleRecorded"), new RuntimeException("decode error"));

    verify(alertWebhookClient, never()).send(any());
  }

  @Test
  void occurrenceCount10TriggersAlert() {
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull())).thenReturn(10L);

    recorder.record(record("ExpenseRecorded"), new RuntimeException("avro error"));

    verify(alertWebhookClient).send(any(AlertPayload.class));
  }

  @Test
  void occurrenceCount100TriggersAlert() {
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull())).thenReturn(100L);

    recorder.record(record("LaborCostAllocated"), new RuntimeException("timeout"));

    verify(alertWebhookClient).send(any(AlertPayload.class));
  }

  @Test
  void occurrenceCount2000TriggersAlert() {
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull())).thenReturn(2000L);

    recorder.record(record("PayrollPosted"), new RuntimeException("schema mismatch"));

    verify(alertWebhookClient).send(any(AlertPayload.class));
  }

  @Test
  void occurrenceCount999DoesNotTriggerAlert() {
    when(errorInboxWriter.record(any(), anyString(), isNull(), isNull())).thenReturn(999L);

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
        .thenReturn(0L);

    recorder.record(record("SaleRecorded"), new RuntimeException("error"));

    verify(errorInboxWriter).record(any(), eq("kafka:SaleRecorded"), isNull(), isNull());
  }
}
