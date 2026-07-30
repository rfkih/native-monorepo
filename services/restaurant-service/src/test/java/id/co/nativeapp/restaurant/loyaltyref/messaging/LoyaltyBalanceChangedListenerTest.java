package id.co.nativeapp.restaurant.loyaltyref.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.loyaltyref.service.LoyaltyBalanceChangedService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the {@link LoyaltyBalanceChangedListener} FAIL-CLOSED paths (no broker, no Spring
 * context — mirrors {@code outletref.messaging.UserOutletAssignmentListenerTest} verbatim):
 *
 * <ol>
 *   <li>Record WITHOUT an {@code id} header → {@link LoyaltyRefMissingEventIdException}.
 *   <li>Record with a NON-UUID {@code id} header → same exception.
 *   <li>Record with a valid id but an undecodable payload → {@link LoyaltyRefDecodeException}.
 *   <li>Happy path: valid header + valid Avro payload → the decoded event reaches {@link
 *       LoyaltyBalanceChangedService#apply} with the header UUID as the idempotency key.
 * </ol>
 */
class LoyaltyBalanceChangedListenerTest {

  private final LoyaltyBalanceChangedService service = mock(LoyaltyBalanceChangedService.class);
  private final LoyaltyBalanceChangedListener listener = new LoyaltyBalanceChangedListener(service);

  private static ConsumerRecord<String, byte[]> record(byte[] value) {
    return new ConsumerRecord<>("LoyaltyBalanceChanged", 0, 7L, "key", value);
  }

  private static byte[] validPayload() {
    GenericRecord record = new GenericData.Record(LoyaltyBalanceChangedConsumerSchema.schema());
    record.put("member_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("points_balance", 1_500L);
    record.put("balance_seq", 3L);
    record.put("reason", "EARNED");
    record.put("occurred_at", 1_750_000_000_000L);
    return AvroSerde.serialize(record);
  }

  @Test
  void missingIdHeaderFailsClosed() {
    ConsumerRecord<String, byte[]> rec = record(validPayload()); // no "id" header added

    assertThatThrownBy(() -> listener.onLoyaltyBalanceChanged(rec))
        .isInstanceOf(LoyaltyRefMissingEventIdException.class);
    verify(service, never()).apply(any());
  }

  @Test
  void nonUuidIdHeaderFailsClosed() {
    ConsumerRecord<String, byte[]> rec = record(validPayload());
    rec.headers().add("id", "not-a-uuid".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> listener.onLoyaltyBalanceChanged(rec))
        .isInstanceOf(LoyaltyRefMissingEventIdException.class);
    verify(service, never()).apply(any());
  }

  @Test
  void undecodablePayloadFailsClosedAsDecodeException() {
    ConsumerRecord<String, byte[]> rec = record(new byte[] {0x00, 0x01, 0x02});
    rec.headers().add("id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> listener.onLoyaltyBalanceChanged(rec))
        .isInstanceOf(LoyaltyRefDecodeException.class);
    verify(service, never()).apply(any());
  }

  @Test
  void validRecordIsDecodedAndApplied() {
    UUID eventId = UUID.randomUUID();
    ConsumerRecord<String, byte[]> rec = record(validPayload());
    rec.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
    when(service.apply(any())).thenReturn(true);

    listener.onLoyaltyBalanceChanged(rec);

    ArgumentCaptor<LoyaltyBalanceChangedEvent> captor =
        ArgumentCaptor.forClass(LoyaltyBalanceChangedEvent.class);
    verify(service).apply(captor.capture());
    LoyaltyBalanceChangedEvent event = captor.getValue();
    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.memberId()).isEqualTo(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    assertThat(event.companyId()).isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(event.pointsBalance()).isEqualTo(1_500L);
    assertThat(event.balanceSeq()).isEqualTo(3L);
  }

  @Test
  void redeliveredEventIsAppliedButLoggedAsSkippedWhenServiceReportsFalse() {
    UUID eventId = UUID.randomUUID();
    ConsumerRecord<String, byte[]> rec = record(validPayload());
    rec.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
    when(service.apply(any())).thenReturn(false); // already processed / stale balanceSeq

    // Must not throw — a duplicate/stale delivery is a clean, logged no-op, not an error.
    listener.onLoyaltyBalanceChanged(rec);

    verify(service).apply(any());
  }
}
