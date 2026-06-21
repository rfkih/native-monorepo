package id.co.nativeapp.errorinbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The DLT recorder facade: milestone gating + PII-safe alert egress + never-throws contract. */
class ConsumeErrorRecorderTest {

  private final ErrorInboxWriter writer = mock(ErrorInboxWriter.class);
  private final AlertWebhookClient webhook = mock(AlertWebhookClient.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);
  private final ConsumeErrorRecorder recorder =
      new ConsumeErrorRecorder(writer, webhook, clock, "carwash-service");

  private static ConsumerRecord<String, byte[]> record() {
    return new ConsumerRecord<>("SaleRecorded", 0, 0L, "k", new byte[] {1});
  }

  @Test
  void milestonesFireExactlyAtTheBoundaries() {
    assertThat(ConsumeErrorRecorder.shouldAlert(1)).isTrue();
    assertThat(ConsumeErrorRecorder.shouldAlert(2)).isFalse();
    assertThat(ConsumeErrorRecorder.shouldAlert(10)).isTrue();
    assertThat(ConsumeErrorRecorder.shouldAlert(11)).isFalse();
    assertThat(ConsumeErrorRecorder.shouldAlert(100)).isTrue();
    assertThat(ConsumeErrorRecorder.shouldAlert(999)).isFalse();
    assertThat(ConsumeErrorRecorder.shouldAlert(1000)).isTrue();
    assertThat(ConsumeErrorRecorder.shouldAlert(2000)).isTrue();
    assertThat(ConsumeErrorRecorder.shouldAlert(2500)).isFalse();
  }

  @Test
  void firesAlertOnAMilestoneWithTheRedactedMessageAndServiceName() {
    when(writer.record(any(), eq("kafka:SaleRecorded"), isNull(), any()))
        .thenReturn(new ErrorInboxWriter.Recorded(1L, "fp123", "redacted-only"));

    recorder.record(record(), new IllegalStateException("raw boom user@x.com"));

    ArgumentCaptor<AlertPayload> sent = ArgumentCaptor.forClass(AlertPayload.class);
    verify(webhook).send(sent.capture());
    AlertPayload p = sent.getValue();
    assertThat(p.service()).isEqualTo("carwash-service");
    assertThat(p.fingerprint()).isEqualTo("fp123");
    assertThat(p.source()).isEqualTo("kafka:SaleRecorded");
    // The egress carries ONLY the redacted message — never the raw cause text (HR-6).
    assertThat(p.message()).isEqualTo("redacted-only");
  }

  @Test
  void doesNotAlertBelowAMilestone() {
    when(writer.record(any(), any(), isNull(), any()))
        .thenReturn(new ErrorInboxWriter.Recorded(2L, "fp", "msg"));
    recorder.record(record(), new IllegalStateException("boom"));
    verify(webhook, never()).send(any());
  }

  @Test
  void neverThrowsEvenIfTheWriterThrows() {
    when(writer.record(any(), any(), any(), any())).thenThrow(new RuntimeException("db down"));
    // Must not propagate — ops infra must never break the DLT path that follows.
    recorder.record(record(), new IllegalStateException("boom"));
    verify(webhook, never()).send(any());
  }
}
