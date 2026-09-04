package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import id.co.nativeapp.employee.payroll.messaging.CompanyCreatedListener;
import id.co.nativeapp.employee.payroll.messaging.MissingPayrollEventIdException;
import id.co.nativeapp.employee.payroll.messaging.PayrollEventDecodeException;
import id.co.nativeapp.employee.payroll.service.PayrollBootstrapService;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code CompanyCreated} listener FAILS CLOSED (§3.2, HR-3): a missing or non-UUID {@code id}
 * header, or a poison payload, throws a NON-RETRYABLE exception (both registered in {@code
 * KafkaConfig#kafkaErrorHandler}) so the record is routed straight to {@code CompanyCreated.DLT}
 * rather than processed under a synthesised id — which would defeat dedupe after a rebalance /
 * compacted replay and risk double-bootstrapping a company. Pure unit test — no Spring, no Kafka;
 * the service is never reached on any of these poison paths.
 */
class CompanyCreatedListenerTest {

  private final PayrollBootstrapService service = mock(PayrollBootstrapService.class);
  private final CompanyCreatedListener listener = new CompanyCreatedListener(service);

  private static ConsumerRecord<String, byte[]> recordWith(byte[] value) {
    return new ConsumerRecord<>(CompanyCreatedListenerTest.topic(), 0, 0L, "key", value);
  }

  private static String topic() {
    return "CompanyCreated";
  }

  @Test
  void missingIdHeaderFailsClosed() {
    ConsumerRecord<String, byte[]> record = recordWith(new byte[] {1, 2, 3});
    assertThatThrownBy(() -> listener.onCompanyCreated(record))
        .isInstanceOf(MissingPayrollEventIdException.class);
    verifyNoInteractions(service);
  }

  @Test
  void nonUuidIdHeaderFailsClosed() {
    ConsumerRecord<String, byte[]> record = recordWith(new byte[] {1, 2, 3});
    record.headers().add("id", "not-a-uuid".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> listener.onCompanyCreated(record))
        .isInstanceOf(MissingPayrollEventIdException.class);
    verifyNoInteractions(service);
  }

  @Test
  void poisonPayloadWithAValidIdIsDeadLettered() {
    ConsumerRecord<String, byte[]> record = recordWith(new byte[] {(byte) 0xFF, (byte) 0xFF});
    record
        .headers()
        .add("id", "11111111-1111-1111-1111-111111111111".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> listener.onCompanyCreated(record))
        .isInstanceOf(PayrollEventDecodeException.class);
    verifyNoInteractions(service);
  }
}
