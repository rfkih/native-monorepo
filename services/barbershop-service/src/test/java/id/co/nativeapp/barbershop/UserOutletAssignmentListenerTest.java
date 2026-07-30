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
import id.co.nativeapp.barbershop.outletref.messaging.UserOutletAssignmentChangedSchemas;
import id.co.nativeapp.barbershop.outletref.messaging.UserOutletAssignmentEvent;
import id.co.nativeapp.barbershop.outletref.messaging.UserOutletAssignmentListener;
import id.co.nativeapp.barbershop.outletref.service.UserOutletAssignmentRefService;
import id.co.nativeapp.events.AvroSerde;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the {@link UserOutletAssignmentListener} FAIL-CLOSED paths (no broker, no Spring
 * context — the container-level retry/DLT wiring is configured in {@code KafkaConfig}; these tests
 * prove the listener throws the non-retryable exception types that wiring routes to the DLT).
 *
 * <p>Ported from carwash-service's {@code UserOutletAssignmentListenerTest}, flattened into
 * barbershop-service's top-level test package to match this service's own test convention (every
 * {@code barbershop-service} test class lives directly under {@code id.co.nativeapp.barbershop},
 * not nested per feature). Also retargeted to the SHARED {@link EventDecodeException} / {@link
 * MissingEventIdException} rather than a feature-local pair — see {@link
 * UserOutletAssignmentListener}'s javadoc for why.
 *
 * <ol>
 *   <li>Record WITHOUT an {@code id} header → {@link MissingEventIdException} (a producer-side
 *       contract violation must never be silently applied).
 *   <li>Record with a NON-UUID {@code id} header → same exception.
 *   <li>Record with a valid id but an undecodable payload → {@link EventDecodeException} (poison
 *       payload, deterministically non-retryable).
 *   <li>Happy path: valid header + valid Avro payload → the decoded event reaches {@link
 *       UserOutletAssignmentRefService#apply} with the header UUID as the idempotency key.
 * </ol>
 */
class UserOutletAssignmentListenerTest {

  private final UserOutletAssignmentRefService refService =
      mock(UserOutletAssignmentRefService.class);

  private final UserOutletAssignmentListener listener =
      new UserOutletAssignmentListener(refService);

  private static ConsumerRecord<String, byte[]> record(byte[] value) {
    return new ConsumerRecord<>("UserOutletAssignmentChanged", 0, 42L, "key", value);
  }

  private static byte[] validPayload() {
    GenericRecord record = new GenericData.Record(UserOutletAssignmentChangedSchemas.schema());
    record.put("assignment_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    record.put("user_id", "kc-attendant-01");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("org_unit_id", "22222222-2222-2222-2222-222222222222");
    record.put("change_kind", "ASSIGNED");
    record.put("effective_from", (int) LocalDate.of(2026, 7, 27).toEpochDay());
    record.put("effective_to", (int) LocalDate.of(9999, 12, 31).toEpochDay());
    return AvroSerde.serialize(record);
  }

  @Test
  void missingIdHeaderFailsClosed() {
    ConsumerRecord<String, byte[]> rec = record(validPayload()); // no "id" header added

    assertThatThrownBy(() -> listener.onUserOutletAssignmentChanged(rec))
        .isInstanceOf(MissingEventIdException.class);
    verify(refService, never()).apply(any());
  }

  @Test
  void nonUuidIdHeaderFailsClosed() {
    ConsumerRecord<String, byte[]> rec = record(validPayload());
    rec.headers().add("id", "not-a-uuid".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> listener.onUserOutletAssignmentChanged(rec))
        .isInstanceOf(MissingEventIdException.class);
    verify(refService, never()).apply(any());
  }

  @Test
  void undecodablePayloadFailsClosedAsDecodeException() {
    ConsumerRecord<String, byte[]> rec = record(new byte[] {0x00, 0x01, 0x02});
    rec.headers().add("id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> listener.onUserOutletAssignmentChanged(rec))
        .isInstanceOf(EventDecodeException.class);
    verify(refService, never()).apply(any());
  }

  @Test
  void validRecordIsDecodedAndApplied() {
    UUID eventId = UUID.randomUUID();
    ConsumerRecord<String, byte[]> rec = record(validPayload());
    rec.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
    when(refService.apply(any())).thenReturn(true);

    listener.onUserOutletAssignmentChanged(rec);

    ArgumentCaptor<UserOutletAssignmentEvent> captor =
        ArgumentCaptor.forClass(UserOutletAssignmentEvent.class);
    verify(refService).apply(captor.capture());
    UserOutletAssignmentEvent event = captor.getValue();
    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.userId()).isEqualTo("kc-attendant-01");
    assertThat(event.companyId()).isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(event.isActive()).isTrue();
  }
}
