package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import id.co.nativeapp.employee.employee.dto.AddContractCommand;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.employee.payroll.dto.PayrollRunResponse;
import id.co.nativeapp.employee.payroll.dto.RunPayrollCommand;
import id.co.nativeapp.employee.payroll.messaging.PayrollPostedSchema;
import id.co.nativeapp.employee.payroll.service.CompensationWriter;
import id.co.nativeapp.employee.payroll.service.IllustrativeStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.OfficialStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.PayrollRunReader;
import id.co.nativeapp.employee.payroll.service.PayrollRunService;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end proof for ADR 0031 review finding C1 (fresh-context review of Track P phase P2, commit
 * 6c5e8ff): after {@code seed-illustrative} THEN {@code seed-official}, a REAL payroll run must NOT
 * be flagged illustrative.
 *
 * <p>Before the fix, {@code PayrollRunWriter.resolveStatutoryRules} resolved EVERY active {@code
 * statutory_rule} row effective as-of the run date — including the illustrative {@code
 * PPH21_PROGRESSIVE} row, which the official-dataset activation never touched (it has no official
 * counterpart {@code rule_key}; only {@code BPJS_KESEHATAN}/{@code PTKP_RELIEF} share a key with a
 * dataset row and got superseded). {@code freezeRuleSet} OR-folds {@code rule.isIllustrative()}
 * across the WHOLE resolved set regardless of whether any {@code pay_component} references a rule,
 * so that one orphaned illustrative row kept every future run flagged {@code
 * uses_illustrative_rules = true} forever — even one computed entirely from OFFICIAL BPJS/PPh21-TER
 * rules. The fix ({@code OfficialStatutorySeedWriter}'s orphan sweep) deactivates any ACTIVE {@code
 * ILLUSTRATIVE_PLACEHOLDER} row whose {@code rule_key} is not in the activated dataset.
 *
 * <p>Asserts the flag on BOTH surfaces a real consumer reads it from: the persisted {@code
 * payroll_run.uses_illustrative_rules} column (via {@link PayrollRunReader}) AND the {@code
 * PayrollPosted} outbox row's Avro payload, decoded straight off the {@code outbox} table (the SAME
 * bytes finance/the console actually consume) — a fix that only touched one surface would still lie
 * to a real reader of the other.
 */
@SpringBootTest
class OfficialDatasetIllustrativeFlagEndToEndTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "hr-admin-a@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String IDR = "IDR";
  private static final long BASE_MINOR = 20_000_000L;

  @Autowired private IllustrativeStatutorySeedWriter illustrativeSeeder;
  @Autowired private OfficialStatutorySeedWriter officialSeeder;
  @Autowired private EmployeeService employeeService;
  @Autowired private CompensationWriter compensationWriter;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private PayrollRunService payrollRunService;
  @Autowired private AssignmentService assignmentService;
  @Autowired private PayrollRunReader payrollRunReader;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void afterSeedOfficialARealPayrollRunIsNotFlaggedIllustrativeOnAnySurface() throws Exception {
    UUID runId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR);
              // THE FIX UNDER TEST: after this call, PPH21_PROGRESSIVE (the illustrative rule
              // with no official counterpart rule_key) must be DEACTIVATED by the orphan sweep,
              // not left resolving alongside PPH21_TER on every future run.
              officialSeeder.seed("ID-2026.1");

              UUID outlet = UUID.randomUUID();
              orgProjectionService.apply(
                  new OrgUnitProjectedEvent(
                      UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true));

              UUID employeeId =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Wulan", "TK0", "3209555555555555", "6666777788889999"))
                      .getId();
              var contract =
                  employeeService.addContract(
                      new AddContractCommand(
                          employeeId,
                          "PERMANENT",
                          LEGAL_EMPLOYER,
                          LocalDate.of(2026, 1, 1),
                          LocalDate.of(9999, 12, 31)));
              compensationWriter.createPackage(
                  employeeId,
                  contract.getId(),
                  Money.ofMinor(BASE_MINOR, IDR),
                  LocalDate.of(2026, 1, 1),
                  LocalDate.of(9999, 12, 31));
              assignmentService.add(
                  new AddAssignmentCommand(
                      employeeId,
                      outlet,
                      null,
                      "cashier",
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(9999, 12, 31)));

              return payrollRunService
                  .calculateAndPost(
                      new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR)
                  .getId();
            });

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findRun(runId).orElseThrow());
    assertThat(run.status()).isEqualTo("POSTED");
    // THE C1 PROOF (persisted column).
    assertThat(run.usesIllustrativeRules()).isFalse();

    // THE C1 PROOF (outbox payload) — decode the PayrollPosted row's Avro bytes off the outbox
    // table and assert the field agrees with the persisted column.
    Map<String, Object> posted =
        jdbcTemplate.queryForMap("SELECT payload FROM outbox WHERE event_type = 'PayrollPosted'");
    byte[] postedBytes = (byte[]) posted.get("payload");
    GenericRecord postedRecord = AvroSerde.deserialize(postedBytes, PayrollPostedSchema.schema());
    assertThat(postedRecord.get("uses_illustrative_rules")).isEqualTo(false);
  }
}
