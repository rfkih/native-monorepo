package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import id.co.nativeapp.employee.employee.domain.EmploymentContract;
import id.co.nativeapp.employee.employee.dto.AddContractCommand;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.dto.UpdateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.employee.payroll.domain.CompensationPackage;
import id.co.nativeapp.employee.payroll.dto.RunPayrollCommand;
import id.co.nativeapp.employee.payroll.service.CompensationWriter;
import id.co.nativeapp.employee.payroll.service.IllustrativeStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.OfficialStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.PayrollReportReader;
import id.co.nativeapp.employee.payroll.service.PayrollRunService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@link PayrollReportReader} over a real RLS-enforcing PostgreSQL (Track P phase P9), seeded with
 * the OFFICIAL {@code ID-2026.1} dataset — every figure below is regulation-real (the SAME golden
 * 10,000,000 IDR / TK0 / NPWP monthly TER figures {@code PayrollAnnualTrueUpEndToEndTest} and
 * {@code GrossToNetOfficialDatasetTest} already hand-derive and pin: 100,000 BPJS-Kes-EE / 400,000
 * -ER, 200,000 JHT-EE / 370,000 -ER, 100,000 JP-EE / 200,000 -ER, 54,000 JKK-ER, 30,000 JKM-ER,
 * 262,100 PPh21 TER), never illustrative placeholders.
 */
@SpringBootTest
class PayrollReportReaderTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "hr-admin-report@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String IDR = "IDR";
  private static final String DATASET_VERSION = "ID-2026.1";
  private static final long BASE_MINOR = 10_000_000L;
  private static final long PPH21_TER_MINOR = 262_100L; // golden: NPWP, TK0, 10,000,000 base

  @Autowired private IllustrativeStatutorySeedWriter illustrativeSeeder;
  @Autowired private OfficialStatutorySeedWriter officialSeeder;
  @Autowired private EmployeeService employeeService;
  @Autowired private CompensationWriter compensationWriter;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private AssignmentService assignmentService;
  @Autowired private PayrollRunService payrollRunService;
  @Autowired private PayrollReportReader payrollReportReader;

  // ---------------------------------------------------------------------
  // pph21-monthly — AGGREGATE only, zero PII in the output
  // ---------------------------------------------------------------------

  @Test
  void pph21MonthlyAggregatesTwoEmployeesWithZeroPiiInTheOutput() throws Exception {
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          illustrativeSeeder.seed(IDR);
          officialSeeder.seed(DATASET_VERSION);
          UUID outlet = openOutlet();
          UUID withNpwp = tenMillionTk0Employee(outlet, "Budi Santoso", "3209200000000001", true);
          UUID withoutNpwp = tenMillionTk0Employee(outlet, "Siti Rahma", "3209200000000002", false);
          payrollRunService.calculateAndPost(
              new RunPayrollCommand("2026-06", List.of(withNpwp, withoutNpwp), List.of()), IDR);
          return null;
        });

    String csv =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> payrollReportReader.pph21Monthly("2026-06"));

    // gross_bruto = 2 x 10,000,000; pph21 = 262,100 (NPWP) + 314,520 (no-NPWP, x1.20 surcharge).
    assertThat(csv)
        .contains(
            "2026-06,2,1,"
                + (2 * BASE_MINOR)
                + ","
                + (PPH21_TER_MINOR + (PPH21_TER_MINOR * 12 / 10))
                + ",IDR");
    // Zero PII (rule 6): no employee name, no NIK, anywhere in the aggregate export.
    assertThat(csv)
        .doesNotContain("Budi")
        .doesNotContain("Siti")
        .doesNotContain("3209200000000001");
  }

  @Test
  void pph21MonthlyIsHeaderOnlyForAPeriodWithNoActivity() throws Exception {
    TenantContext.runAs(TENANT_A, ACTOR_A, () -> illustrativeSeeder.seed(IDR));
    String csv =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> payrollReportReader.pph21Monthly("2020-01"));
    assertThat(csv)
        .contains("period,headcount,no_npwp_count,gross_bruto_minor,pph21_withheld_minor,currency");
    assertThat(csv.lines().count()).isEqualTo(2); // the leading comment + the column header only
  }

  // ---------------------------------------------------------------------
  // bpjs-summary — per-employee, per-program; formula-injection guard on the employee name
  // ---------------------------------------------------------------------

  @Test
  void bpjsSummaryBreaksDownEachEmployeeAcrossAllFiveProgramsAndGuardsAFormulaInjectionName()
      throws Exception {
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          illustrativeSeeder.seed(IDR);
          officialSeeder.seed(DATASET_VERSION);
          UUID outlet = openOutlet();
          UUID employeeId = tenMillionTk0Employee(outlet, "=SUM(A1:A9)", "3209200000000003", true);
          payrollRunService.calculateAndPost(
              new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR);
          return null;
        });

    String csv =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> payrollReportReader.bpjsSummary("2026-06"));

    // The formula-injection guard: the raw "=SUM(...)" name never appears un-guarded (it is always
    // preceded by a leading single-quote once escaped, and the whole cell is RFC-4180 quoted since
    // it also contains commas).
    assertThat(csv).doesNotContain("\n=SUM").doesNotContain(",=SUM");
    assertThat(csv).contains("'=SUM(A1:A9)");

    assertThat(csv).contains("KESEHATAN,10000000,100000,400000,IDR");
    assertThat(csv).contains("JHT,10000000,200000,370000,IDR");
    assertThat(csv).contains("JP,10000000,100000,200000,IDR");
    assertThat(csv).contains("JKK,10000000,0,54000,IDR");
    assertThat(csv).contains("JKM,10000000,0,30000,IDR");
  }

  // ---------------------------------------------------------------------
  // 1721-A1 — annual per-employee, recomputed biaya jabatan / pengurang / PTKP / PKP / annual PPh21
  // ---------------------------------------------------------------------

  @Test
  void bukti1721A1SumsTwoActiveMonthsWithRecomputedAnnualFigures() throws Exception {
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          illustrativeSeeder.seed(IDR);
          officialSeeder.seed(DATASET_VERSION);
          UUID outlet = openOutlet();
          UUID employeeId = tenMillionTk0Employee(outlet, "Wulan Ayu", "3209200000000004", true);
          payrollRunService.calculateAndPost(
              new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR);
          payrollRunService.calculateAndPost(
              new RunPayrollCommand("2026-07", List.of(employeeId), List.of()), IDR);
          return null;
        });

    String csv =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> payrollReportReader.bukti1721A1("2026"));

    // annual_bruto=20,000,000; biaya_jabatan=min(5%*20,000,000, 6,000,000*2/12)=1,000,000;
    // pengurang=1,000,000+600,000(JHT/JP-EE x2 months)=1,600,000; PTKP(TK0)=54,000,000
    // (unprorated);
    // net = 20,000,000-1,600,000-54,000,000 < 0 -> floored 0 -> PKP 0 -> annual PPh21 0;
    // withheld = 2 x 262,100 = 524,200 (the REAL sum of the monthly TER lines).
    assertThat(csv).contains("Wulan Ayu,20000000,1000000,1600000,54000000,0,0,524200,IDR");
    assertThat(csv).contains("# Bukti Potong 1721-A1");
  }

  @Test
  void bukti1721A1ExcludesASupersededRunFromTheAnnualSum() throws Exception {
    Map<String, UUID> ids =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR);
              officialSeeder.seed(DATASET_VERSION);
              UUID outlet = openOutlet();
              UUID employeeId =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Dimas Aditya", "TK0", "3209200000000005", "3209200000000005"))
                      .getId();
              employeeService.update(
                  new UpdateEmployeeCommand(
                      employeeId, null, null, null, null, "923200000000005", null, null));
              EmploymentContract contract =
                  employeeService.addContract(
                      new AddContractCommand(
                          employeeId,
                          "PERMANENT",
                          LEGAL_EMPLOYER,
                          LocalDate.of(2026, 1, 1),
                          LocalDate.of(9999, 12, 31)));
              // A STALE, materially different base (20,000,000) — run_seq=1.
              CompensationPackage stale =
                  compensationWriter.createPackage(
                      employeeId,
                      contract.getId(),
                      Money.ofMinor(20_000_000L, IDR),
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
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR);

              // Correct it down to the golden 10,000,000 base and re-run June — run_seq=2
              // SUPERSEDES run_seq=1.
              compensationWriter.endPackage(employeeId, stale.getId(), LocalDate.of(2026, 5, 31));
              compensationWriter.createPackage(
                  employeeId,
                  contract.getId(),
                  Money.ofMinor(BASE_MINOR, IDR),
                  LocalDate.of(2026, 6, 1),
                  LocalDate.of(9999, 12, 31));
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR);
              return Map.of("employee", employeeId);
            });

    String csv =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> payrollReportReader.bukti1721A1("2026"));

    // ONLY the active run_seq=2's 10,000,000 base contributes — annual_bruto is 10,000,000, NOT
    // 30,000,000 (which a bug summing the superseded run_seq=1 alongside it would produce).
    assertThat(csv).contains("Dimas Aditya,10000000,");
    assertThat(csv).doesNotContain("Dimas Aditya,30000000,");
    assertThat(csv).doesNotContain("Dimas Aditya,20000000,");
  }

  @Test
  void bukti1721A1IsHeaderOnlyForAYearWithNoActivity() throws Exception {
    TenantContext.runAs(TENANT_A, ACTOR_A, () -> illustrativeSeeder.seed(IDR));
    String csv =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> payrollReportReader.bukti1721A1("2019"));
    assertThat(csv).contains("nik,npwp,full_name,annual_bruto_minor");
    assertThat(csv).contains("# row_count=0");
  }

  // ----------------------------------------------------------------------- helpers

  private UUID openOutlet() {
    UUID outlet = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true));
    return outlet;
  }

  /** A TK0 employee with a single 10,000,000 IDR base package, assigned to {@code outlet}. */
  private UUID tenMillionTk0Employee(UUID outlet, String fullName, String nik, boolean withNpwp) {
    UUID employeeId =
        employeeService.create(new CreateEmployeeCommand(fullName, "TK0", nik, nik)).getId();
    if (withNpwp) {
      employeeService.update(
          new UpdateEmployeeCommand(
              employeeId, null, null, null, null, "9" + nik.substring(1), null, null));
    }
    EmploymentContract contract =
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
    return employeeId;
  }
}
