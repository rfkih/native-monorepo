package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import id.co.nativeapp.employee.employee.dto.AddContractCommand;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.employee.payroll.domain.CompensationPackage;
import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import id.co.nativeapp.employee.payroll.dto.MetricProjectedEvent;
import id.co.nativeapp.employee.payroll.dto.PayrollRunResponse;
import id.co.nativeapp.employee.payroll.dto.RunPayrollCommand;
import id.co.nativeapp.employee.payroll.messaging.LaborCostAllocatedSchema;
import id.co.nativeapp.employee.payroll.messaging.PayrollPostedSchema;
import id.co.nativeapp.employee.payroll.service.CompensationWriter;
import id.co.nativeapp.employee.payroll.service.IllustrativeStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.MetricInputProjectionService;
import id.co.nativeapp.employee.payroll.service.PayrollRunReader;
import id.co.nativeapp.employee.payroll.service.PayrollRunService;
import id.co.nativeapp.employee.payroll.service.PayrollRunWriter;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end payroll run over a real RLS-enforcing PostgreSQL: seed illustrative rules + catalog,
 * set up one employee with a comp package + assignment, run payroll, and assert the totals, the
 * outbox events (PayrollPosted + LaborCostAllocated, NO PII), the illustrative flag + loud WARN,
 * the salary-absence from logs and events (HR-6), and byte-identical re-runs (HR-7).
 */
@SpringBootTest
class PayrollRunEndToEndTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "hr-admin-a@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String IDR = "IDR";
  private static final long BASE_MINOR = 20_000_000L;

  @Autowired private IllustrativeStatutorySeedWriter seeder;
  @Autowired private EmployeeService employeeService;
  @Autowired private CompensationWriter compensationWriter;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private PayrollRunService payrollRunService;
  @Autowired private AssignmentService assignmentService;
  @Autowired private PayrollRunReader payrollRunReader;
  @Autowired private MetricInputProjectionService metricProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void runsPayrollPostsEventsFlagsIllustrativeAndKeepsSalaryOutOfLogsAndEvents() throws Exception {
    Logger employeeLogger = (Logger) LoggerFactory.getLogger("id.co.nativeapp.employee");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    employeeLogger.addAppender(appender);
    employeeLogger.setLevel(Level.TRACE);

    UUID runId;
    try {
      runId = TenantContext.callAs(TENANT_A, ACTOR_A, () -> setUpAndRun());
    } finally {
      employeeLogger.detachAppender(appender);
    }

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findRun(runId).orElseThrow());
    assertThat(run.grossTotalMinor()).isEqualTo(20_000_000L);
    assertThat(run.employeeDeductionTotalMinor()).isEqualTo(2_201_667L);
    assertThat(run.employerContributionTotalMinor()).isEqualTo(400_000L);
    assertThat(run.netTotalMinor()).isEqualTo(17_798_333L);
    assertThat(run.status()).isEqualTo("POSTED");
    assertThat(run.usesIllustrativeRules()).isTrue();

    Map<String, Object> posted =
        jdbcTemplate.queryForMap("SELECT payload FROM outbox WHERE event_type = 'PayrollPosted'");
    byte[] postedBytes = (byte[]) posted.get("payload");
    GenericRecord postedRecord = AvroSerde.deserialize(postedBytes, PayrollPostedSchema.schema());
    assertThat(postedRecord.get("net_total_minor")).isEqualTo(17_798_333L);
    assertThat(postedRecord.get("uses_illustrative_rules")).isEqualTo(true);
    // run_seq is populated from payroll_run.run_seq on the producer wire: the first run is 1.
    assertThat(postedRecord.get("run_seq")).isEqualTo(1);
    assertThat(((List<?>) postedRecord.get("rule_versions"))).isNotEmpty();

    List<Map<String, Object>> allocated =
        jdbcTemplate.queryForList(
            "SELECT payload FROM outbox WHERE event_type = 'LaborCostAllocated'");
    assertThat(allocated).hasSize(1);
    GenericRecord allocRecord =
        AvroSerde.deserialize(
            (byte[]) allocated.get(0).get("payload"), LaborCostAllocatedSchema.schema());
    assertThat(allocRecord.get("amount_minor")).isEqualTo(20_400_000L);
    assertThat(allocRecord.get("uses_illustrative_rules")).isEqualTo(true);
    // run_seq is populated from payroll_run.run_seq on the producer wire: the first run is 1.
    assertThat(allocRecord.get("run_seq")).isEqualTo(1);

    // HR-6: the individual NET salary figure must not appear in any event payload.
    assertThat(new String(postedBytes, StandardCharsets.UTF_8))
        .doesNotContain(Long.toString(17_798_333L));
    for (Map<String, Object> ev : allocated) {
      String text = new String((byte[]) ev.get("payload"), StandardCharsets.UTF_8);
      assertThat(text).doesNotContain(Long.toString(17_798_333L));
    }

    // HR-6 + HR-9: salary absent from logs; the loud illustrative WARN present without PII.
    boolean warned = false;
    for (ILoggingEvent event : appender.list) {
      String line = event.getFormattedMessage();
      assertThat(line).doesNotContain(Long.toString(17_798_333L));
      if (line.contains("ILLUSTRATIVE_PLACEHOLDER statutory figures")) {
        warned = true;
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
      }
    }
    assertThat(warned).as("the loud illustrative WARN is logged").isTrue();
  }

  @Test
  void reRunOverTheSameFrozenRulesAndInputsIsByteIdentical() throws Exception {
    UUID[] holder = new UUID[1];
    TenantContext.runAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          seeder.seed(IDR);
          UUID outlet = UUID.randomUUID();
          orgProjectionService.apply(
              new OrgUnitProjectedEvent(
                  UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true));
          UUID employeeId =
              employeeService
                  .create(
                      new CreateEmployeeCommand(
                          "Siti", "TK0", "3209999999999999", "5555666677778888"))
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
          holder[0] = employeeId;
        });

    UUID employeeId = holder[0];
    PayrollRun run1 =
        callRun(
            () ->
                payrollRunService.calculate(
                    new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR));
    PayrollRun run2 =
        callRun(
            () ->
                payrollRunService.calculate(
                    new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR));

    assertThat(run2.getRunSeq()).isGreaterThan(run1.getRunSeq());
    assertThat(run1.getGrossTotal()).isEqualTo(run2.getGrossTotal());
    assertThat(run1.getNetTotal()).isEqualTo(run2.getNetTotal());
    assertThat(run1.getEmployerContributionTotal()).isEqualTo(run2.getEmployerContributionTotal());
    assertThat(run1.getRuleVersionSetJson()).isEqualTo(run2.getRuleVersionSetJson());
  }

  @Test
  void dailyMetricCommissionFlowsThroughGrossToNetIntoTheRunTotals() throws Exception {
    // CRITICAL 1 proof: a PER_METRIC_UNIT commission earning driven by DAILY-grain metrics
    // ("2026-06-DD"). resolveMetricEarning must SUM the daily rows within the run month, not look
    // up only the bare month "2026-06" (which would never match a daily row and silently resolve
    // to zero). 12 + 8 + 10 = 30 washes * 50,000 rate = 1,500,000 commission.
    long rate = 50_000L;
    long washesDay1 = 12L;
    long washesDay2 = 8L;
    long washesDay3 = 10L;
    long expectedCommission = (washesDay1 + washesDay2 + washesDay3) * rate; // 1,500,000

    UUID runId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              seeder.seed(IDR);
              UUID outlet = UUID.randomUUID();
              orgProjectionService.apply(
                  new OrgUnitProjectedEvent(
                      UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true));

              // A COMMISSION earning component (taxable). Inserted over the admin/BYPASSRLS
              // connection because a direct repository save from the test is not inside a tenant-
              // scoped @Transactional, so the RLS WITH CHECK (which the seeder/writers satisfy via
              // the auto-RLS aspect) would reject it.
              UUID commissionId = insertCommissionComponentAsAdmin();

              // Three DAILY-grain wash_count rows for the outlet, all WITHIN the run month.
              projectDailyWash(outlet, "2026-06-01", washesDay1);
              projectDailyWash(outlet, "2026-06-15", washesDay2);
              projectDailyWash(outlet, "2026-06-28", washesDay3);
              // A neighbouring-month daily row that must be EXCLUDED by the month-prefix match.
              projectDailyWash(outlet, "2026-07-01", 999L);

              UUID employeeId =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Sari", "TK0", "3209888888888888", "9999000011112222"))
                      .getId();
              var contract =
                  employeeService.addContract(
                      new AddContractCommand(
                          employeeId,
                          "PERMANENT",
                          LEGAL_EMPLOYER,
                          LocalDate.of(2026, 1, 1),
                          LocalDate.of(9999, 12, 31)));
              CompensationPackage pkg =
                  compensationWriter.createPackage(
                      employeeId,
                      contract.getId(),
                      Money.ofMinor(BASE_MINOR, IDR),
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(9999, 12, 31));
              compensationWriter.addPerMetricEarning(
                  pkg.getId(),
                  commissionId,
                  "wash_count",
                  Money.ofMinor(rate, IDR),
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
    // Gross = base 20,000,000 + commission 1,500,000 = 21,500,000. The commission is NOT zero —
    // proving the daily-grain metric resolved through gross-to-net into the run totals.
    assertThat(run.grossTotalMinor()).isEqualTo(BASE_MINOR + expectedCommission);
    assertThat(run.grossTotalMinor()).isEqualTo(21_500_000L);
  }

  @Test
  void aMixedPopulationRunWithOneNoOutletEmployeeCompletesViaTheUnallocatedSuspenseBucket()
      throws Exception {
    // CRITICAL 2 proof (a): a MIXED run — one employee WITH an outlet, one WITHOUT (on leave /
    // between assignments). The no-outlet person must NOT abort the whole run with an internal
    // assertion error; their employer labor cost is routed to the UNALLOCATED suspense bucket, and
    // the run-level exact-sum invariant still holds (calculateAndPost would throw otherwise).
    UUID runId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              seeder.seed(IDR);
              UUID outlet = UUID.randomUUID();
              orgProjectionService.apply(
                  new OrgUnitProjectedEvent(
                      UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true));

              UUID assigned =
                  makeEmployeeWithPackage("Budi", "3201111111111111", "1111222233334444");
              assignmentService.add(
                  new AddAssignmentCommand(
                      assigned,
                      outlet,
                      null,
                      "cashier",
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(9999, 12, 31)));

              // The no-outlet employee: a comp package but NO assignment in the period.
              UUID unassigned =
                  makeEmployeeWithPackage("Dewi", "3202222222222222", "5555666677778888");

              return payrollRunService
                  .calculateAndPost(
                      new RunPayrollCommand("2026-06", List.of(assigned, unassigned), List.of()),
                      IDR)
                  .getId();
            });

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findRun(runId).orElseThrow());
    assertThat(run.status()).isEqualTo("POSTED");
    // Two employees at the same base -> gross 40,000,000, employer BPJS 800,000.
    assertThat(run.grossTotalMinor()).isEqualTo(2 * BASE_MINOR);

    // The UNALLOCATED suspense bucket exists and is marked on the LaborCostAllocated event.
    List<Map<String, Object>> allocated =
        jdbcTemplate.queryForList(
            "SELECT payload FROM outbox WHERE event_type = 'LaborCostAllocated'");
    boolean foundUnallocated = false;
    long realOutletSum = 0L;
    long unallocatedSum = 0L;
    for (Map<String, Object> ev : allocated) {
      GenericRecord rec =
          AvroSerde.deserialize((byte[]) ev.get("payload"), LaborCostAllocatedSchema.schema());
      long amount = (long) rec.get("amount_minor");
      if ((boolean) rec.get("unallocated")) {
        foundUnallocated = true;
        unallocatedSum += amount;
        assertThat(rec.get("gl_account").toString()).isEqualTo("9999-UNALLOCATED-LABOR");
        assertThat(rec.get("outlet_id").toString())
            .isEqualTo(PayrollRunWriter.UNALLOCATED_OUTLET.toString());
      } else {
        realOutletSum += amount;
      }
    }
    assertThat(foundUnallocated).as("an UNALLOCATED suspense bucket was emitted").isTrue();
    // Each person's labor cost = gross 20,000,000 + employer BPJS 400,000 = 20,400,000.
    assertThat(unallocatedSum).isEqualTo(20_400_000L); // the no-outlet person
    assertThat(realOutletSum).isEqualTo(20_400_000L); // the assigned person
    // Exact-sum across buckets == run labor total (gross + employer contributions).
    assertThat(realOutletSum + unallocatedSum)
        .isEqualTo(run.grossTotalMinor() + run.employerContributionTotalMinor());
  }

  @Test
  void salaryCiphertextAtRestHasNoPlaintextDigitsOnTheRawJdbcColumns() throws Exception {
    UUID runId = TenantContext.callAs(TENANT_A, ACTOR_A, () -> setUpAndRun());
    assertThat(runId).isNotNull();

    // Read the raw encrypted columns straight off the JDBC connection (admin/BYPASSRLS) and assert
    // the plaintext salary digits are ABSENT — ciphertext at rest (rule 6), complementing the
    // converter-level MoneyPiiConverterTest.
    String basePay = Long.toString(BASE_MINOR); // 20000000
    String netDigits = Long.toString(17_798_333L);
    for (String enc : queryAsAdmin("SELECT base_pay_enc AS v FROM comp_package")) {
      assertThat(enc).isNotBlank().doesNotContain(basePay).doesNotContain("IDR");
    }
    List<String> amountEncs = queryAsAdmin("SELECT amount_enc AS v FROM payslip_line");
    assertThat(amountEncs).isNotEmpty();
    for (String enc : amountEncs) {
      assertThat(enc).isNotBlank().doesNotContain(basePay).doesNotContain(netDigits);
    }
  }

  private UUID makeEmployeeWithPackage(String name, String nik, String bankAccount) {
    UUID employeeId =
        employeeService.create(new CreateEmployeeCommand(name, "TK0", nik, bankAccount)).getId();
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
    return employeeId;
  }

  private void projectDailyWash(UUID outlet, String period, long value) {
    metricProjectionService.apply(
        new MetricProjectedEvent(
            UUID.randomUUID(), TENANT_A, "wash_count", period, "outlet", outlet, value));
  }

  /** Inserts a COMMISSION pay component for TENANT_A over the admin/BYPASSRLS connection. */
  private UUID insertCommissionComponentAsAdmin() throws Exception {
    UUID id = UUID.randomUUID();
    String sql =
        "INSERT INTO pay_component (id, component_key, kind, calc_type, bearer, gl_account,"
            + " taxable, statutory_rule_key, display_order, active, created_at, created_by,"
            + " updated_at, updated_by, version, company_id) VALUES ('"
            + id
            + "', 'COMMISSION', 'EARNING', 'FIXED', 'EMPLOYEE', '5120-COMMISSION', true, NULL, 15,"
            + " true, now(), 'test', now(), 'test', 0, '"
            + TENANT_A
            + "')";
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement()) {
      st.executeUpdate(sql);
    }
    return id;
  }

  private List<String> queryAsAdmin(String sql) throws Exception {
    java.util.List<String> values = new java.util.ArrayList<>();
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString("v"));
      }
    }
    return values;
  }

  private UUID setUpAndRun() {
    seeder.seed(IDR);
    UUID outlet = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true));
    UUID employeeId =
        employeeService
            .create(
                new CreateEmployeeCommand("Budi", "TK0", "3201234567890123", "1234567890123456"))
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
        .calculateAndPost(new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR)
        .getId();
  }

  private PayrollRun callRun(Callable<PayrollRun> action) throws Exception {
    return TenantContext.callAs(TENANT_A, ACTOR_A, action);
  }
}
