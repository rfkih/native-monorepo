package id.co.nativeapp.restaurant.loyaltyref.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.loyaltyref.service.GiftCardStateChangedService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the {@link GiftCardStateChangedListener} FAIL-CLOSED paths — mirrors {@link
 * LoyaltyBalanceChangedListenerTest} in every particular (the two listeners share the same
 * decode/missing-id exception types, ADR 0027).
 */
class GiftCardStateChangedListenerTest {

  private final GiftCardStateChangedService service = mock(GiftCardStateChangedService.class);
  private final GiftCardStateChangedListener listener = new GiftCardStateChangedListener(service);

  private static ConsumerRecord<String, byte[]> record(byte[] value) {
    return new ConsumerRecord<>("GiftCardStateChanged", 0, 11L, "key", value);
  }

  private static byte[] validPayload() {
    GenericRecord record = new GenericData.Record(GiftCardStateChangedConsumerSchema.schema());
    record.put("gift_card_id", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("state", "ACTIVE");
    record.put("balance_minor", 25_000L);
    record.put("currency", "IDR");
    record.put("balance_seq", 2L);
    record.put("occurred_at", 1_750_000_000_000L);
    return AvroSerde.serialize(record);
  }

  @Test
  void missingIdHeaderFailsClosed() {
    ConsumerRecord<String, byte[]> rec = record(validPayload());

    assertThatThrownBy(() -> listener.onGiftCardStateChanged(rec))
        .isInstanceOf(LoyaltyRefMissingEventIdException.class);
    verify(service, never()).apply(any());
  }

  @Test
  void nonUuidIdHeaderFailsClosed() {
    ConsumerRecord<String, byte[]> rec = record(validPayload());
    rec.headers().add("id", "not-a-uuid".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> listener.onGiftCardStateChanged(rec))
        .isInstanceOf(LoyaltyRefMissingEventIdException.class);
    verify(service, never()).apply(any());
  }

  @Test
  void undecodablePayloadFailsClosedAsDecodeException() {
    ConsumerRecord<String, byte[]> rec = record(new byte[] {0x00, 0x01, 0x02});
    rec.headers().add("id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> listener.onGiftCardStateChanged(rec))
        .isInstanceOf(LoyaltyRefDecodeException.class);
    verify(service, never()).apply(any());
  }

  @Test
  void validRecordIsDecodedAndApplied() {
    UUID eventId = UUID.randomUUID();
    ConsumerRecord<String, byte[]> rec = record(validPayload());
    rec.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
    when(service.apply(any())).thenReturn(true);

    listener.onGiftCardStateChanged(rec);

    ArgumentCaptor<GiftCardStateChangedEvent> captor =
        ArgumentCaptor.forClass(GiftCardStateChangedEvent.class);
    verify(service).apply(captor.capture());
    GiftCardStateChangedEvent event = captor.getValue();
    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.giftCardId()).isEqualTo(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
    assertThat(event.companyId()).isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(event.state()).isEqualTo("ACTIVE");
    assertThat(event.balanceMinor()).isEqualTo(25_000L);
    assertThat(event.currency()).isEqualTo("IDR");
    assertThat(event.balanceSeq()).isEqualTo(2L);
  }

  @Test
  void redeliveredEventIsAppliedButLoggedAsSkippedWhenServiceReportsFalse() {
    UUID eventId = UUID.randomUUID();
    ConsumerRecord<String, byte[]> rec = record(validPayload());
    rec.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
    when(service.apply(any())).thenReturn(false);

    listener.onGiftCardStateChanged(rec);

    verify(service).apply(any());
  }
}
