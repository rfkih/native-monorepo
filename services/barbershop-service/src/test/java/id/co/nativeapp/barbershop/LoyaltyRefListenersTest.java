package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.barbershop.config.EventDecodeException;
import id.co.nativeapp.barbershop.config.MissingEventIdException;
import id.co.nativeapp.barbershop.loyaltyref.messaging.GiftCardStateChangedConsumerSchema;
import id.co.nativeapp.barbershop.loyaltyref.messaging.GiftCardStateChangedEvent;
import id.co.nativeapp.barbershop.loyaltyref.messaging.GiftCardStateChangedListener;
import id.co.nativeapp.barbershop.loyaltyref.messaging.LoyaltyBalanceChangedConsumerSchema;
import id.co.nativeapp.barbershop.loyaltyref.messaging.LoyaltyBalanceChangedEvent;
import id.co.nativeapp.barbershop.loyaltyref.messaging.LoyaltyBalanceChangedListener;
import id.co.nativeapp.barbershop.loyaltyref.service.GiftCardStateChangedService;
import id.co.nativeapp.barbershop.loyaltyref.service.LoyaltyBalanceChangedService;
import id.co.nativeapp.events.AvroSerde;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link LoyaltyBalanceChangedListener} and {@link GiftCardStateChangedListener}
 * (ADR 0027, Phase 4) — no broker, no Spring context. Ported from carwash-service's {@code
 * LoyaltyRefListenersTest}: both listeners share barbershop-service's ALREADY-SHARED {@link
 * EventDecodeException} / {@link MissingEventIdException}.
 */
class LoyaltyRefListenersTest {

  @Nested
  class LoyaltyBalanceChangedListenerFailClosedPaths {

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
      ConsumerRecord<String, byte[]> rec = record(validPayload());

      assertThatThrownBy(() -> listener.onLoyaltyBalanceChanged(rec))
          .isInstanceOf(MissingEventIdException.class);
      verify(service, never()).apply(any());
    }

    @Test
    void nonUuidIdHeaderFailsClosed() {
      ConsumerRecord<String, byte[]> rec = record(validPayload());
      rec.headers().add("id", "not-a-uuid".getBytes(StandardCharsets.UTF_8));

      assertThatThrownBy(() -> listener.onLoyaltyBalanceChanged(rec))
          .isInstanceOf(MissingEventIdException.class);
      verify(service, never()).apply(any());
    }

    @Test
    void undecodablePayloadFailsClosedAsDecodeException() {
      ConsumerRecord<String, byte[]> rec = record(new byte[] {0x00, 0x01, 0x02});
      rec.headers().add("id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

      assertThatThrownBy(() -> listener.onLoyaltyBalanceChanged(rec))
          .isInstanceOf(EventDecodeException.class);
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
      assertThat(event.pointsBalance()).isEqualTo(1_500L);
      assertThat(event.balanceSeq()).isEqualTo(3L);
    }

    @Test
    void redeliveredEventIsAppliedButLoggedAsSkippedWhenServiceReportsFalse() {
      UUID eventId = UUID.randomUUID();
      ConsumerRecord<String, byte[]> rec = record(validPayload());
      rec.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
      when(service.apply(any())).thenReturn(false);

      listener.onLoyaltyBalanceChanged(rec); // must not throw

      verify(service).apply(any());
    }
  }

  @Nested
  class GiftCardStateChangedListenerFailClosedPaths {

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
          .isInstanceOf(MissingEventIdException.class);
      verify(service, never()).apply(any());
    }

    @Test
    void nonUuidIdHeaderFailsClosed() {
      ConsumerRecord<String, byte[]> rec = record(validPayload());
      rec.headers().add("id", "not-a-uuid".getBytes(StandardCharsets.UTF_8));

      assertThatThrownBy(() -> listener.onGiftCardStateChanged(rec))
          .isInstanceOf(MissingEventIdException.class);
      verify(service, never()).apply(any());
    }

    @Test
    void undecodablePayloadFailsClosedAsDecodeException() {
      ConsumerRecord<String, byte[]> rec = record(new byte[] {0x00, 0x01, 0x02});
      rec.headers().add("id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

      assertThatThrownBy(() -> listener.onGiftCardStateChanged(rec))
          .isInstanceOf(EventDecodeException.class);
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
      assertThat(event.state()).isEqualTo("ACTIVE");
      assertThat(event.balanceMinor()).isEqualTo(25_000L);
      assertThat(event.balanceSeq()).isEqualTo(2L);
    }

    @Test
    void redeliveredEventIsAppliedButLoggedAsSkippedWhenServiceReportsFalse() {
      UUID eventId = UUID.randomUUID();
      ConsumerRecord<String, byte[]> rec = record(validPayload());
      rec.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
      when(service.apply(any())).thenReturn(false);

      listener.onGiftCardStateChanged(rec); // must not throw

      verify(service).apply(any());
    }
  }
}
