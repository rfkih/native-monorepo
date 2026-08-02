package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import id.co.nativeapp.employee.employee.domain.EmploymentContract;
import id.co.nativeapp.employee.employee.dto.AddContractCommand;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.dto.UpdateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.employee.payroll.domain.MissingHireDateException;
import id.co.nativeapp.employee.payroll.domain.PayslipLine;
import id.co.nativeapp.employee.payroll.domain.RunType;
import id.co.nativeapp.employee.payroll.domain.ThrRunMisconfiguredException;
import id.co.nativeapp.employee.payroll.dto.PayrollRunResponse;
import id.co.nativeapp.employee.payroll.dto.PayslipLineResponse;
import id.co.nativeapp.employee.payroll.dto.RunPayrollCommand;
import id.co.nativeapp.employee.payroll.repository.PayslipLineRepository;
import id.co.nativeapp.employee.payroll.service.CompensationWriter;
import id.co.nativeapp.employee.payroll.service.IllustrativeStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.OfficialStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.PayrollRunReader;
import id.co.nativeapp.employee.payroll.service.PayrollRunService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Track P Phase P8 (THR run type, ADR 0035), end-to-end over a real RLS-enforcing PostgreSQL. Every
 * scenario seeds the OFFICIAL {@code ID-2026.1} -&gt; {@code ID-2026.2} -&gt; {@code ID-2026.3}
 * dataset chain (the {@code ID-2026.3} top-up's own self-contained-topup note documents this is the
 * expected real-world merge order), so every figure below is hand-derivable and regulation-real.
 */
@SpringBootTest
class ThrRunTypeEndToEndTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "hr-admin-a@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String IDR = "IDR";
  private static final String PERIOD = "2026-06";

  @Autowired private IllustrativeStatutorySeedWriter illustrativeSeeder;
  @Autowired private OfficialStatutorySeedWriter officialSeeder;
  @Autowired private EmployeeService employeeService;
  @Autowired private CompensationWriter compensationWriter;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private AssignmentService assignmentService;
  @Autowired private PayrollRunService payrollRunService;
  @Autowired private PayrollRunReader payrollRunReader;
  @Autowired private TestActiveLinesReader activeLinesReader;

  /**
   * A real Spring-proxied, {@code @Transactional} read of {@link
   * PayslipLineRepository#findActiveLinesForEmployeeYear} — a BARE repository call from a test
   * method is NOT wrapped by {@code RlsAutoApplyAspect} (only a genuinely {@code @Transactional}
   * bean method is, the {@code *Writer}/{@code *Reader} pattern), so a raw call would run unbound
   * and RLS would fail closed (empty). Mirrors {@code PayrollWorkInputsEndToEndTest
   * .TestCustomComponentSeeder}'s exact rationale.
   */
  @TestConfiguration
  static class TestActiveLinesReaderConfig {
    @Bean
    TestActiveLinesReader testActiveLinesReader(PayslipLineRepository payslipLineRepository) {
      return new TestActiveLinesReader(payslipLineRepository);
    }
  }

  static class TestActiveLinesReader {
    private final PayslipLineRepository payslipLineRepository;

    TestActiveLinesReader(PayslipLineRepository payslipLineRepository) {
      this.payslipLineRepository = payslipLineRepository;
    }

    @Transactional(readOnly = true)
    List<PayslipLine> activeLinesForYear(UUID employeeId, String yearPrefix, String excludePeriod) {
      return payslipLineRepository.findActiveLinesForEmployeeYear(
          employeeId, yearPrefix, excludePeriod);
    }
  }

  // -----------------------------------------------------------------------
  // THR proration (Permenaker 6/2016: months of service / 12, capped at 1x for >=12 months)
  // -----------------------------------------------------------------------

  @Test
  void thirteenMonthsTenurePaysOneTimesBase() throws Exception {
    // Hired 2025-01-01, run period 2026-06 -> 17 complete months (>=12), capped at 12/12 = 1.0x.
    long baseMinor = 10_000_000L;
    UUID employeeId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                setUpThrEmployee("Wulan", "3209000000000101", baseMinor, LocalDate.of(2025, 1, 1)));

    UUID runId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                payrollRunService
                    .calculateAndPost(
                        new RunPayrollCommand(
                            PERIOD, List.of(employeeId), List.of(), RunType.THR, null),
                        IDR)
                    .getId());

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findRun(runId).orElseThrow());
    assertThat(run.status()).isEqualTo("POSTED");
    assertThat(run.runType()).isEqualTo("THR");
    assertThat(thrLine(runId, employeeId).amountMinor()).isEqualTo(baseMinor);
  }

  @Test
  void sixMonthsTenurePaysHalfBase() throws Exception {
    // Hired 2025-12-30, run period 2026-06 -> EXACTLY 6 complete months -> 6/12 = 0.5x.
    long baseMinor = 12_000_000L;
    UUID employeeId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                setUpThrEmployee(
                    "Aditya", "3209000000000102", baseMinor, LocalDate.of(2025, 12, 30)));

    UUID runId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                payrollRunService
                    .calculateAndPost(
                        new RunPayrollCommand(
                            PERIOD, List.of(employeeId), List.of(), RunType.THR, null),
                        IDR)
                    .getId());

    assertThat(thrLine(runId, employeeId).amountMinor()).isEqualTo(6_000_000L);
  }

  @Test
  void lessThanOneMonthTenureIsExcludedFromThr() throws Exception {
    // Hired 2026-06-15, run period 2026-06 -> 0 complete months -> excluded (0).
    long baseMinor = 10_000_000L;
    UUID employeeId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                setUpThrEmployee(
                    "Citra", "3209000000000103", baseMinor, LocalDate.of(2026, 6, 15)));

    UUID runId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                payrollRunService
                    .calculateAndPost(
                        new RunPayrollCommand(
                            PERIOD, List.of(employeeId), List.of(), RunType.THR, null),
                        IDR)
                    .getId());

    assertThat(thrLine(runId, employeeId).amountMinor()).isZero();
  }

  @Test
  void hireDateFallsBackToTheEarliestAssignmentEffectiveFromWhenAbsent() throws Exception {
    // No hire_date on file; the earliest of two assignments (2025-01-01, before a later
    // 2025-06-01 one) is the fallback -> 17 complete months to 2026-06-30 -> capped 1.0x.
    long baseMinor = 8_000_000L;
    UUID employeeId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR);
              officialSeeder.seed("ID-2026.1");
              officialSeeder.seed("ID-2026.2");
              officialSeeder.seed("ID-2026.3");
              UUID outlet = openOutlet();
              UUID id =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Dimas", "TK0", "3209000000000104", "5555000011112222"))
                      .getId();
              EmploymentContract contract = addPermanentContract(id);
              compensationWriter.createPackage(
                  id,
                  contract.getId(),
                  Money.ofMinor(baseMinor, IDR),
                  LocalDate.of(2024, 1, 1),
                  LocalDate.of(9999, 12, 31));
              // A LATER assignment first...
              assignmentService.add(
                  new AddAssignmentCommand(
                      id,
                      outlet,
                      null,
                      "cashier",
                      LocalDate.of(2025, 6, 1),
                      LocalDate.of(9999, 12, 31)));
              return id;
            });
    // ...then an EARLIER one added second (proves the fallback picks the MINIMUM, not the first
    // one created).
    TenantContext.runAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          UUID outlet2 = openOutlet();
          assignmentService.add(
              new AddAssignmentCommand(
                  employeeId,
                  outlet2,
                  null,
                  "barista",
                  LocalDate.of(2025, 1, 1),
                  LocalDate.of(2025, 5, 31)));
        });

    UUID runId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                payrollRunService
                    .calculateAndPost(
                        new RunPayrollCommand(
                            PERIOD, List.of(employeeId), List.of(), RunType.THR, null),
                        IDR)
                    .getId());

    assertThat(thrLine(runId, employeeId).amountMinor()).isEqualTo(baseMinor);
  }

  @Test
  void missingHireDateAndNoAssignmentFailsTheThrRunLoudly() throws Exception {
    UUID employeeId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR);
              officialSeeder.seed("ID-2026.1");
              officialSeeder.seed("ID-2026.2");
              officialSeeder.seed("ID-2026.3");
              UUID id =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Farhan", "TK0", "3209000000000105", "1111222233334444"))
                      .getId();
              EmploymentContract contract = addPermanentContract(id);
              compensationWriter.createPackage(
                  id,
                  contract.getId(),
                  Money.ofMinor(10_000_000L, IDR),
                  LocalDate.of(2024, 1, 1),
                  LocalDate.of(9999, 12, 31));
              // Deliberately NO assignment at all.
              return id;
            });

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () ->
                        payrollRunService.calculate(
                            new RunPayrollCommand(
                                PERIOD, List.of(employeeId), List.of(), RunType.THR, null),
                            IDR)))
        .isInstanceOf(MissingHireDateException.class);
  }

  @Test
  void aThrRunFailsLoudlyWhenTheDatasetTopUpIsNotActivated() throws Exception {
    // ID-2026.3 never seeded: BASE's run_scope stays ALL -> the double-pay guard fires first.
    UUID employeeId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR);
              officialSeeder.seed("ID-2026.1");
              UUID outlet = openOutlet();
              UUID id =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Gilang", "TK0", "3209000000000106", "6666777788889999"))
                      .getId();
              EmploymentContract contract = addPermanentContract(id);
              compensationWriter.createPackage(
                  id,
                  contract.getId(),
                  Money.ofMinor(10_000_000L, IDR),
                  LocalDate.of(2024, 1, 1),
                  LocalDate.of(9999, 12, 31));
              assignmentService.add(
                  new AddAssignmentCommand(
                      id,
                      outlet,
                      null,
                      "cashier",
                      LocalDate.of(2024, 1, 1),
                      LocalDate.of(9999, 12, 31)));
              return id;
            });

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () ->
                        payrollRunService.calculate(
                            new RunPayrollCommand(
                                PERIOD, List.of(employeeId), List.of(), RunType.THR, null),
                            IDR)))
        .isInstanceOf(ThrRunMisconfiguredException.class)
        .hasMessageContaining("run_scope=ALL");
  }

  // -----------------------------------------------------------------------
  // run_scope filtering (no BPJS on THR)
  // -----------------------------------------------------------------------

  @Test
  void aThrRunCarriesOnlyTheThrEarningAndPph21NeverBpjs() throws Exception {
    long baseMinor = 10_000_000L;
    UUID employeeId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                setUpThrEmployee("Hana", "3209000000000107", baseMinor, LocalDate.of(2024, 1, 1)));

    UUID runId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                payrollRunService
                    .calculateAndPost(
                        new RunPayrollCommand(
                            PERIOD, List.of(employeeId), List.of(), RunType.THR, null),
                        IDR)
                    .getId());

    List<PayslipLineResponse> lines =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findPayslipAuthorized(runId, employeeId));
    List<String> componentKeys = lines.stream().map(PayslipLineResponse::componentKey).toList();
    assertThat(componentKeys).containsExactlyInAnyOrder("THR", "PPH21");
    assertThat(componentKeys)
        .noneMatch(
            key ->
                key.equals("BASE")
                    || key.startsWith("BPJS")
                    || key.equals("JHT_EE")
                    || key.equals("JHT_ER")
                    || key.equals("JP_EE")
                    || key.equals("JP_ER")
                    || key.equals("JKK_ER")
                    || key.equals("JKM_ER"));
  }

  // -----------------------------------------------------------------------
  // The THR-month combined-gross TER interplay — BOTH execution orders reach the SAME total
  // withheld (ADR 0035 §5's order-invariance proof: rate(combinedGross) x combinedGross,
  // regardless of which of the pair computes first).
  // -----------------------------------------------------------------------

  @Test
  void combinedGrossTerReachesTheSameTotalWithheldRegardlessOfExecutionOrder() throws Exception {
    long baseMinor = 10_000_000L;

    // Employee A: REGULAR runs FIRST, THR SECOND.
    UUID employeeA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                setUpThrEmployee("Indra", "3209000000000108", baseMinor, LocalDate.of(2024, 1, 1)));
    UUID regularRunA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                payrollRunService
                    .calculateAndPost(
                        new RunPayrollCommand(
                            PERIOD, List.of(employeeA), List.of(), RunType.REGULAR, null),
                        IDR)
                    .getId());
    UUID thrRunA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                payrollRunService
                    .calculateAndPost(
                        new RunPayrollCommand(
                            PERIOD, List.of(employeeA), List.of(), RunType.THR, null),
                        IDR)
                    .getId());

    // grossBruto(REGULAR alone) = 10,000,000 base + 484,000 taxable ER legs (BPJS-Kes-ER 400,000 +
    // JKK-ER 54,000 + JKM-ER 30,000) = 10,484,000 -> TER category A rate 250bp (<=10,700,000) ->
    // REGULAR-first withheld = 262,100 (matches the P3 golden test's hand-derived figure exactly).
    long regularAloneWithheld = 262_100L;
    assertThat(pph21Line(regularRunA, employeeA).amountMinor()).isEqualTo(regularAloneWithheld);

    // combinedGrossBruto = 10,000,000 (THR, no ER legs) + 10,484,000 (REGULAR sibling) =
    // 20,484,000 -> rate 900bp (<=24,150,000, >19,750,000) -> combinedTax = 1,843,560.
    long combinedTax = 1_843_560L;
    long thrSecondWithheld = combinedTax - regularAloneWithheld; // 1,581,460
    assertThat(pph21Line(thrRunA, employeeA).amountMinor()).isEqualTo(thrSecondWithheld);
    long totalWithheldOrderA = regularAloneWithheld + thrSecondWithheld;
    assertThat(totalWithheldOrderA).isEqualTo(combinedTax);

    // Employee B: THR runs FIRST, REGULAR SECOND — the reversed order.
    UUID employeeB =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                setUpThrEmployee("Joko", "3209000000000109", baseMinor, LocalDate.of(2024, 1, 1)));
    UUID thrRunB =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                payrollRunService
                    .calculateAndPost(
                        new RunPayrollCommand(
                            PERIOD, List.of(employeeB), List.of(), RunType.THR, null),
                        IDR)
                    .getId());
    UUID regularRunB =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                payrollRunService
                    .calculateAndPost(
                        new RunPayrollCommand(
                            PERIOD, List.of(employeeB), List.of(), RunType.REGULAR, null),
                        IDR)
                    .getId());

    // grossBruto(THR alone) = 10,000,000 (no ER legs on THR) -> TER category A rate 200bp
    // (<=10,050,000) -> THR-first withheld = 200,000.
    long thrAloneWithheld = 200_000L;
    assertThat(pph21Line(thrRunB, employeeB).amountMinor()).isEqualTo(thrAloneWithheld);

    // combinedGrossBruto = 10,484,000 (REGULAR) + 10,000,000 (THR sibling) = 20,484,000 — the
    // SAME combined gross as order A (commutative sum) -> the SAME combinedTax 1,843,560.
    long regularSecondWithheld = combinedTax - thrAloneWithheld; // 1,643,560
    assertThat(pph21Line(regularRunB, employeeB).amountMinor()).isEqualTo(regularSecondWithheld);
    long totalWithheldOrderB = thrAloneWithheld + regularSecondWithheld;
    assertThat(totalWithheldOrderB).isEqualTo(combinedTax);

    // THE ORDER-INVARIANCE PROOF: both orders net the SAME total withheld for the period, despite
    // a completely different per-run split.
    assertThat(totalWithheldOrderA).isEqualTo(totalWithheldOrderB);
  }

  // -----------------------------------------------------------------------
  // The ACTIVE_RUN_PREDICATE run_type fix (the P3-review landmine) — extends the P3 supersession
  // test family: a REGULAR correction's run_seq racing numerically ahead of an independent THR
  // run's run_seq for the SAME period must NOT cause either to wrongly "supersede" the other.
  // -----------------------------------------------------------------------

  @Test
  void aRegularCorrectionsHigherRunSeqNeverSupersedesAnIndependentThrRunForTheSamePeriod()
      throws Exception {
    long staleBaseMinor = 9_000_000L;
    long finalBaseMinor = 10_000_000L;

    Map<String, UUID> ids =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR);
              officialSeeder.seed("ID-2026.1");
              officialSeeder.seed("ID-2026.2");
              officialSeeder.seed("ID-2026.3");
              UUID outlet = openOutlet();
              UUID employeeId =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Kirana", "TK0", "3209000000000110", "1212121212121212"))
                      .getId();
              employeeService.update(
                  new UpdateEmployeeCommand(
                      employeeId, null, null, null, null, null, null, LocalDate.of(2024, 1, 1)));
              EmploymentContract contract = addPermanentContract(employeeId);
              var stalePkg =
                  compensationWriter.createPackage(
                      employeeId,
                      contract.getId(),
                      Money.ofMinor(staleBaseMinor, IDR),
                      LocalDate.of(2024, 1, 1),
                      LocalDate.of(9999, 12, 31));
              assignmentService.add(
                  new AddAssignmentCommand(
                      employeeId,
                      outlet,
                      null,
                      "cashier",
                      LocalDate.of(2024, 1, 1),
                      LocalDate.of(9999, 12, 31)));

              // REGULAR run_seq=1 at the STALE base.
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand(
                      PERIOD, List.of(employeeId), List.of(), RunType.REGULAR, null),
                  IDR);

              // Correct the package; REGULAR run_seq=2 at the FINAL base -> supersedes run_seq=1
              // WITHIN the REGULAR type. This numerically pushes REGULAR's run_seq to 2, strictly
              // ahead of the THR run below (which is still at run_seq=1 in its OWN series).
              compensationWriter.endPackage(
                  employeeId, stalePkg.getId(), LocalDate.of(2026, 5, 31));
              compensationWriter.createPackage(
                  employeeId,
                  contract.getId(),
                  Money.ofMinor(finalBaseMinor, IDR),
                  LocalDate.of(2026, 6, 1),
                  LocalDate.of(9999, 12, 31));
              UUID regularRun2 =
                  payrollRunService
                      .calculateAndPost(
                          new RunPayrollCommand(
                              PERIOD, List.of(employeeId), List.of(), RunType.REGULAR, null),
                          IDR)
                      .getId();

              // THR run_seq=1 — its OWN independent series, numerically BEHIND REGULAR's run_seq=2.
              // The pre-fix predicate (MAX(run_seq) WHERE period=P, no run_type scoping) would have
              // wrongly treated ONLY run_seq=2 (REGULAR) as "active" for the period, excluding this
              // THR run_seq=1 as if REGULAR had superseded it.
              UUID thrRun1 =
                  payrollRunService
                      .calculateAndPost(
                          new RunPayrollCommand(
                              PERIOD, List.of(employeeId), List.of(), RunType.THR, null),
                          IDR)
                      .getId();

              return Map.of("employee", employeeId, "regularRun2", regularRun2, "thrRun1", thrRun1);
            });

    // The repository-level proof (exactly where the fix lives): findActiveLinesForEmployeeYear for
    // "2026", excluding a DIFFERENT period ("2026-12" — this employee has no December run), must
    // return BOTH the ACTIVE REGULAR run_seq=2 lines (at the FINAL base) AND the THR run_seq=1
    // lines — never excluding the THR run as if it had been superseded, and never including the
    // SUPERSEDED REGULAR run_seq=1's stale-base lines.
    List<PayslipLine> activeLines =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> activeLinesReader.activeLinesForYear(ids.get("employee"), "2026", "2026-12"));

    List<String> componentKeys = activeLines.stream().map(PayslipLine::getComponentKey).toList();
    // THR's line is present — NOT wrongly excluded by REGULAR's numerically-higher run_seq.
    assertThat(componentKeys).contains("THR");
    // The BASE line reflects the FINAL (run_seq=2) base amount, not the stale run_seq=1 one —
    // ordinary within-type supersession still holds.
    PayslipLine baseLine =
        activeLines.stream()
            .filter(l -> "BASE".equals(l.getComponentKey()))
            .findFirst()
            .orElseThrow();
    assertThat(baseLine.getAmount()).isEqualTo(Money.ofMinor(finalBaseMinor, IDR));
    // Exactly ONE BASE line (the stale run_seq=1's BASE line is excluded, not double-counted).
    assertThat(activeLines.stream().filter(l -> "BASE".equals(l.getComponentKey())).count())
        .isEqualTo(1L);
  }

  // -----------------------------------------------------------------------
  // Documented residual: the December Art-17 true-up WARNs when a same-period THR sibling exists
  // (it does not yet compose with it) — ADR 0035 §6.
  // -----------------------------------------------------------------------

  @Test
  void aDecemberRegularRunWarnsWhenAnActiveThrSiblingExistsForTheSamePeriod() throws Exception {
    long baseMinor = 10_000_000L;
    UUID employeeId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                setUpThrEmployee(
                    "Lestari", "3209000000000111", baseMinor, LocalDate.of(2024, 1, 1)));

    // THR first for December.
    TenantContext.runAs(
        TENANT_A,
        ACTOR_A,
        () ->
            payrollRunService.calculateAndPost(
                new RunPayrollCommand("2026-12", List.of(employeeId), List.of(), RunType.THR, null),
                IDR));

    Logger employeeLogger = (Logger) LoggerFactory.getLogger("id.co.nativeapp.employee");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    employeeLogger.addAppender(appender);
    employeeLogger.setLevel(Level.WARN);
    try {
      TenantContext.runAs(
          TENANT_A,
          ACTOR_A,
          () ->
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand(
                      "2026-12", List.of(employeeId), List.of(), RunType.REGULAR, null),
                  IDR));
    } finally {
      employeeLogger.detachAppender(appender);
    }

    boolean warned =
        appender.list.stream()
            .anyMatch(
                e ->
                    e.getFormattedMessage().contains("December Art-17 true-up")
                        && e.getFormattedMessage().contains("THR run also exists"));
    assertThat(warned).as("the documented-residual WARN fires").isTrue();
  }

  // -----------------------------------------------------------------------
  // Fixtures
  // -----------------------------------------------------------------------

  private UUID openOutlet() {
    UUID outlet = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true));
    return outlet;
  }

  private EmploymentContract addPermanentContract(UUID employeeId) {
    return employeeService.addContract(
        new AddContractCommand(
            employeeId,
            "PERMANENT",
            LEGAL_EMPLOYER,
            LocalDate.of(2024, 1, 1),
            LocalDate.of(9999, 12, 31)));
  }

  /**
   * The full P8 dataset chain + a TK0/NPWP'd, hire-dated employee with a single monthly base
   * package and an outlet assignment covering {@link #PERIOD}. NPWP is set to keep the arithmetic
   * free of the no-NPWP x120% surcharge.
   */
  private UUID setUpThrEmployee(String fullName, String nik, long baseMinor, LocalDate hireDate) {
    illustrativeSeeder.seed(IDR);
    officialSeeder.seed("ID-2026.1");
    officialSeeder.seed("ID-2026.2");
    officialSeeder.seed("ID-2026.3");
    UUID outlet = openOutlet();
    UUID employeeId =
        employeeService.create(new CreateEmployeeCommand(fullName, "TK0", nik, nik)).getId();
    employeeService.update(
        new UpdateEmployeeCommand(
            employeeId, null, null, null, null, "9" + nik.substring(1), null, hireDate));
    EmploymentContract contract = addPermanentContract(employeeId);
    compensationWriter.createPackage(
        employeeId,
        contract.getId(),
        Money.ofMinor(baseMinor, IDR),
        LocalDate.of(2024, 1, 1),
        LocalDate.of(9999, 12, 31));
    assignmentService.add(
        new AddAssignmentCommand(
            employeeId,
            outlet,
            null,
            "cashier",
            LocalDate.of(2024, 1, 1),
            LocalDate.of(9999, 12, 31)));
    return employeeId;
  }

  private PayslipLineResponse thrLine(UUID runId, UUID employeeId) throws Exception {
    List<PayslipLineResponse> lines =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findPayslipAuthorized(runId, employeeId));
    return lines.stream()
        .filter(l -> l.componentKey().equals("THR"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no THR line for employee " + employeeId));
  }

  private PayslipLineResponse pph21Line(UUID runId, UUID employeeId) throws Exception {
    List<PayslipLineResponse> lines =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findPayslipAuthorized(runId, employeeId));
    return lines.stream()
        .filter(l -> l.componentKey().equals("PPH21"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no PPH21 line for employee " + employeeId));
  }
}
