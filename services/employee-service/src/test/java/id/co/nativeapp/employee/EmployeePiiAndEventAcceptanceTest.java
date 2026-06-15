package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.messaging.EmployeeChangedSchema;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test (a) — the PII + event acceptance proof over a real RLS-enforcing PostgreSQL.
 *
 * <p>Creating an employee:
 *
 * <ul>
 *   <li>stores the NIK and bank account ENCRYPTED at rest — the raw DB column is ciphertext, NOT
 *       the plaintext (asserted over the admin/BYPASSRLS connection), and it decrypts back to the
 *       original via the application read path (rule 6);
 *   <li>returns the PII masked (NIK redacted, bank account last-4) — never the plaintext;
 *   <li>keeps the PII out of the logs (a log-capture assertion across the whole create flow); and
 *   <li>emits exactly one {@code EmployeeChanged} to the outbox, with NO PII in the payload.
 * </ul>
 */
@SpringBootTest
class EmployeePiiAndEventAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "hr-admin-a@example.co.id";

  // Synthetic PII (not real). The test proves these never reach the DB column / logs / event in
  // clear.
  private static final String NIK = "3201234567890123";
  private static final String BANK_ACCOUNT = "1234567890123456";

  @Autowired private EmployeeService employeeService;
  @Autowired private id.co.nativeapp.employee.employee.service.EmployeeReader employeeReader;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private id.co.nativeapp.employee.config.PiiCipher piiCipher;

  @Test
  void
      creatingAnEmployeeEncryptsPiiAtRestMasksItInTheResponseKeepsItOutOfLogsAndEmitsEmployeeChanged()
          throws Exception {
    // Attach a log-capturing appender to the whole service package so any accidental PII log is
    // seen.
    Logger rootEmployeeLogger = (Logger) LoggerFactory.getLogger("id.co.nativeapp.employee");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    rootEmployeeLogger.addAppender(appender);
    rootEmployeeLogger.setLevel(Level.TRACE);

    UUID employeeId;
    try {
      employeeId =
          TenantContext.callAs(
              TENANT_A,
              ACTOR_A,
              () -> {
                Employee created =
                    employeeService.create(
                        new CreateEmployeeCommand("Budi Santoso", "K1", NIK, BANK_ACCOUNT));
                // The aggregate masks PII for any response (rule 6).
                assertThat(created.maskedNik()).isEqualTo("***REDACTED***");
                assertThat(created.maskedBankAccount()).isEqualTo("****3456");
                return created.getId();
              });
    } finally {
      rootEmployeeLogger.detachAppender(appender);
    }

    // (1) The raw DB column is CIPHERTEXT, not the plaintext (read over the admin/BYPASSRLS conn).
    Map<String, Object> row = employeeRowAsAdmin(employeeId);
    String storedNik = (String) row.get("nik");
    String storedBank = (String) row.get("bank_account");
    assertThat(storedNik).isNotEqualTo(NIK).doesNotContain(NIK);
    assertThat(storedBank).isNotEqualTo(BANK_ACCOUNT).doesNotContain(BANK_ACCOUNT);
    // The stored ciphertext decrypts back to the ORIGINAL plaintext (the same key the converter
    // uses).
    assertThat(piiCipher.decryptFromString(storedNik)).isEqualTo(NIK);
    assertThat(piiCipher.decryptFromString(storedBank)).isEqualTo(BANK_ACCOUNT);

    // (2) ...and it decrypts back to the original via the application read path (RLS-scoped to A).
    //     The reader goes through a @Transactional service so the auto-RLS aspect engages; the
    //     converter decrypts on read, so the masked forms it returns are derived from the ORIGINAL
    //     plaintext — proving the stored ciphertext round-trips back to the same value.
    var reread =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> employeeReader.findWithAssignments(employeeId).orElseThrow());
    assertThat(reread.employee().maskedNik()).isEqualTo("***REDACTED***");
    assertThat(reread.employee().maskedBankAccount()).isEqualTo("****3456");

    // (3) No PII anywhere in the captured logs (rule 6).
    List<ILoggingEvent> logs = appender.list;
    for (ILoggingEvent event : logs) {
      String line = event.getFormattedMessage();
      assertThat(line).doesNotContain(NIK).doesNotContain(BANK_ACCOUNT);
    }

    // (4) Exactly one EmployeeChanged to the outbox, with NO PII in the payload.
    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT aggregate_id, payload FROM outbox WHERE event_type = 'EmployeeChanged'");
    assertThat(events).hasSize(1);
    Map<String, Object> emitted = events.get(0);
    assertThat(emitted.get("aggregate_id")).isEqualTo(employeeId.toString());

    GenericRecord decoded =
        AvroSerde.deserialize((byte[]) emitted.get("payload"), EmployeeChangedSchema.schema());
    assertThat(decoded.get("employee_id").toString()).isEqualTo(employeeId.toString());
    assertThat(decoded.get("company_id").toString()).isEqualTo(TENANT_A);
    assertThat(decoded.get("status").toString()).isEqualTo("ACTIVE");
    // The serialized event bytes contain no PII (rule 6).
    String payloadAsText =
        new String((byte[]) emitted.get("payload"), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(payloadAsText).doesNotContain(NIK).doesNotContain(BANK_ACCOUNT);
  }

  /**
   * Reads the employee row's PII columns over the admin (BYPASSRLS) connection — the raw
   * ciphertext.
   */
  private Map<String, Object> employeeRowAsAdmin(UUID employeeId) throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement("SELECT nik, bank_account FROM employee WHERE id = ?")) {
      ps.setObject(1, employeeId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return Map.of("nik", rs.getString("nik"), "bank_account", rs.getString("bank_account"));
      }
    }
  }
}
