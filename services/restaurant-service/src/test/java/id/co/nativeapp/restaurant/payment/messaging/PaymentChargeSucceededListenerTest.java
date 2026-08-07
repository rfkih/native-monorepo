package id.co.nativeapp.restaurant.payment.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.payment.service.PaymentChargeSucceededService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the {@link PaymentChargeSucceededListener} FAIL-CLOSED paths — mirrors {@link
 * id.co.nativeapp.restaurant.loyaltyref.messaging.GiftCardStateChangedListenerTest} in every
 * particular.
 */
class PaymentChargeSucceededListenerTest {

  private final PaymentChargeSucceededService service = mock(PaymentChargeSucceededService.class);
  private final PaymentChargeSucceededListener listener =
      new PaymentChargeSucceededListener(service);

  private static ConsumerRecord<String, byte[]> record(byte[] value) {
    return new ConsumerRecord<>("PaymentChargeSucceeded", 0, 11L, "key", value);
  }

  private static byte[] validPayload() {
    GenericRecord record = new GenericData.Record(PaymentChargeSucceededConsumerSchema.schema());
    record.put("charge_id", "aaaaaaaa-1111-2222-3333-444444444444");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("vertical", "restaurant");
    record.put("payment_id", "bbbbbbbb-1111-2222-3333-444444444444");
    record.put("reference_id", null);
    record.put("business_id", "22222222-2222-2222-2222-222222222222");
    record.put("amount_minor", 185_000L);
    record.put("currency", "IDR");
    record.put("provider", "MIDTRANS");
    record.put("provider_txn_id", "mt-txn-0001");
    record.put("succeeded_at", 1_750_000_000_000L);
    return AvroSerde.serialize(record);
  }

  @Test
  void missingIdHeaderFailsClosed() {
    ConsumerRecord<String, byte[]> rec = record(validPayload());

    assertThatThrownBy(() -> listener.onPaymentChargeSucceeded(rec))
        .isInstanceOf(PaymentChargeSucceededMissingEventIdException.class);
    verify(service, never()).apply(any());
  }

  @Test
  void nonUuidIdHeaderFailsClosed() {
    ConsumerRecord<String, byte[]> rec = record(validPayload());
    rec.headers().add("id", "not-a-uuid".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> listener.onPaymentChargeSucceeded(rec))
        .isInstanceOf(PaymentChargeSucceededMissingEventIdException.class);
    verify(service, never()).apply(any());
  }

  @Test
  void undecodablePayloadFailsClosedAsDecodeException() {
    ConsumerRecord<String, byte[]> rec = record(new byte[] {0x00, 0x01, 0x02});
    rec.headers().add("id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> listener.onPaymentChargeSucceeded(rec))
        .isInstanceOf(PaymentChargeSucceededDecodeException.class);
    verify(service, never()).apply(any());
  }

  @Test
  void validRecordIsDecodedAndApplied() {
    UUID eventId = UUID.randomUUID();
    ConsumerRecord<String, byte[]> rec = record(validPayload());
    rec.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
    when(service.apply(any())).thenReturn(true);

    listener.onPaymentChargeSucceeded(rec);

    ArgumentCaptor<PaymentChargeSucceededEvent> captor =
        ArgumentCaptor.forClass(PaymentChargeSucceededEvent.class);
    verify(service).apply(captor.capture());
    PaymentChargeSucceededEvent event = captor.getValue();
    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.chargeId()).isEqualTo(UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444"));
    assertThat(event.companyId()).isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(event.vertical()).isEqualTo("restaurant");
    assertThat(event.paymentId())
        .isEqualTo(UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444"));
    assertThat(event.referenceId()).isNull();
    assertThat(event.amountMinor()).isEqualTo(185_000L);
    assertThat(event.currency()).isEqualTo("IDR");
  }

  @Test
  void redeliveredEventIsAppliedButLoggedAsSkippedWhenServiceReportsFalse() {
    UUID eventId = UUID.randomUUID();
    ConsumerRecord<String, byte[]> rec = record(validPayload());
    rec.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
    when(service.apply(any())).thenReturn(false);

    listener.onPaymentChargeSucceeded(rec);

    verify(service).apply(any());
  }
}
