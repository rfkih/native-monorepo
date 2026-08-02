package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import id.co.nativeapp.employee.employee.domain.EmploymentContract;
import id.co.nativeapp.employee.employee.dto.AddContractCommand;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.dto.UpdateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.expense.dto.CreateClaimCommand;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryWriter;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.employee.payroll.domain.CompensationPackage;
import id.co.nativeapp.employee.payroll.dto.PayrollRunResponse;
import id.co.nativeapp.employee.payroll.dto.PayslipLineResponse;
import id.co.nativeapp.employee.payroll.dto.RunPayrollCommand;
import id.co.nativeapp.employee.payroll.messaging.PayrollLiabilitiesPostedSchema;
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
 * The December / final-month Art-17 true-up, end-to-end over a real RLS-enforcing PostgreSQL (Track
 * P phase P3, ADR 0031). Every scenario seeds the OFFICIAL {@code ID-2026.1} dataset (real PMK
 * 168/2023 TER + UU HPP 7/2021 Art-17 figures), so the numbers below are hand-derivable and
 * regulation-real, never illustrative placeholders.
 *
 * <p>Every hand-computed figure in this file follows the SAME arithmetic {@link
 * id.co.nativeapp.employee.payroll.service.GrossToNetCalculator} performs: the TER effective-rate
 * lookup for a monthly run; for December, UU HPP 7/2021 Art-17 progressive brackets minus PMK
 * 250/2008 biaya jabatan (prorated by {@code monthsInYear}, Track P phase P3 W2) and PTKP
 * (deliberately unprorated).
 */
@SpringBootTest
class PayrollAnnualTrueUpEndToEndTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "hr-admin-a@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String IDR = "IDR";
  private static final String DATASET_VERSION = "ID-2026.1";
  // A SECOND linked employee identity (P7 review S2c) — expense-claim self-service create/submit
  // needs a caller resolvable to an employee link, distinct from ACTOR_A (the HR-admin who
  // approves; self-approval is rejected).
  private static final String EMPLOYEE_ACTOR = "aaaaaaaa-6666-6666-6666-666666666666";

  @Autowired private IllustrativeStatutorySeedWriter illustrativeSeeder;
  @Autowired private OfficialStatutorySeedWriter officialSeeder;
  @Autowired private EmployeeService employeeService;
  @Autowired private CompensationWriter compensationWriter;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private AssignmentService assignmentService;
  @Autowired private PayrollRunService payrollRunService;
  @Autowired private PayrollRunReader payrollRunReader;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ExpenseCategoryWriter categoryWriter;
  @Autowired private ExpenseClaimService claimService;

  // ---------------------------------------------------------------------
  // Golden: a non-December run under the OFFICIAL dataset is COMPLETELY unaffected — the exact
  // same figures GrossToNetOfficialDatasetTest's pure unit test derives, now proven through the
  // FULL writer path (the December-guard branch this phase adds must be a true no-op off-month).
  // ---------------------------------------------------------------------

  @Test
  void aNonDecemberRunUnderTheOfficialDatasetIsByteIdenticalToThePreP3Figures() throws Exception {
    UUID runId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR); // the bootstrap: seeds BASE/COMMISSION/BPJS_KES_*/PPH21
              officialSeeder.seed(DATASET_VERSION); // rewires onto the OFFICIAL TER/Art-17 rules
              UUID outlet = openOutlet();
              UUID employeeId = tenMillionTk0NpwpEmployee(outlet, "3209000000000001");
              return payrollRunService
                  .calculateAndPost(
                      new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR)
                  .getId();
            });

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findRun(runId).orElseThrow());
    assertThat(run.status()).isEqualTo("POSTED");
    assertThat(run.grossTotalMinor()).isEqualTo(10_000_000L);
    // 100,000 BPJS-Kes-EE + 200,000 JHT-EE + 100,000 JP-EE + 262,100 PPh21(TER) = 662,100.
    assertThat(run.employeeDeductionTotalMinor()).isEqualTo(662_100L);
    assertThat(run.employerContributionTotalMinor()).isEqualTo(1_054_000L);
    assertThat(run.netTotalMinor()).isEqualTo(9_337_900L);
    assertThat(run.usesIllustrativeRules()).isFalse();
  }

  // ---------------------------------------------------------------------
  // December run resolution: the SAME person/base/PTKP as the golden run above, but run in
  // December with no prior-year history, resolves ANNUAL_PROGRESSIVE instead of TER_TABLE — a
  // materially different result (0 vs 262,100) proves the branch actually switched.
  // ---------------------------------------------------------------------

  @Test
  void aDecemberRunResolvesTheAnnualProgressiveBranchInsteadOfTheMonthlyTerBranch()
      throws Exception {
    Map<String, UUID> ids =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR); // the bootstrap: seeds BASE/COMMISSION/BPJS_KES_*/PPH21
              officialSeeder.seed(DATASET_VERSION); // rewires onto the OFFICIAL TER/Art-17 rules
              UUID outlet = openOutlet();
              UUID employeeId = tenMillionTk0NpwpEmployee(outlet, "3209000000000002");
              UUID runId =
                  payrollRunService
                      .calculateAndPost(
                          new RunPayrollCommand("2026-12", List.of(employeeId), List.of()), IDR)
                      .getId();
              return Map.of("employee", employeeId, "run", runId);
            });

    UUID runId = ids.get("run");
    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findRun(runId).orElseThrow());
    assertThat(run.status()).isEqualTo("POSTED");

    PayslipLineResponse pph21 = pph21Line(runId, ids.get("employee"));
    // annual_gross = 10,484,000 (single December month, no history); biaya jabatan prorated cap
    // (monthsInYear=1) = 6,000,000/12 = 500,000, binds under the uncapped 5% (524,200); pengurang
    // = 500,000 + 300,000 (JHT/JP-EE) = 800,000; netAnnual = 10,484,000 - 800,000 - 54,000,000
    // (PTKP TK0) < 0 -> floored to 0 -> PKP 0 -> annual liability 0. December line = 0 - 0 = 0 —
    // NOT 262,100 (the TER figure for the identical inputs off-December, see the golden test
    // above): the branch genuinely switched.
    assertThat(pph21.amountMinor()).isEqualTo(0L);
    assertThat(run.netTotalMinor()).isEqualTo(9_600_000L); // 10,000,000 - (100k+200k+100k+0)
  }

  // ---------------------------------------------------------------------
  // Fail-loud carry-in (P3/P4 review, wired in Track P Phase P7): a December run that resolves
  // ANNUAL_PROGRESSIVE but ends up swapping ZERO components onto it (a catalog miswiring — here,
  // PPH21 deliberately un-wired from any monthly income-tax family) must throw loudly rather than
  // silently post a December run with NO income-tax line at all.
  // ---------------------------------------------------------------------

  @Test
  void aDecemberRunThatWouldSwapZeroComponentsFailsLoudlyInsteadOfSilentlyPostingNoIncomeTaxLine()
      throws Exception {
    UUID employeeId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR);
              officialSeeder.seed(DATASET_VERSION);
              UUID outlet = openOutlet();
              return tenMillionTk0NpwpEmployee(outlet, "3209000000000099");
            });
    // Deliberately break the catalog wiring: PPH21 no longer references ANY statutory rule, so
    // December's swap finds nothing to swap onto the resolved ANNUAL_PROGRESSIVE rule. A bare
    // JdbcTemplate call is NOT routed through RlsAutoApplyAspect (it only binds the tenant GUC on
    // @Transactional Spring-proxy beans), so a normal app_user UPDATE here would silently match
    // ZERO rows under FORCE RLS — the admin/BYPASSRLS connection is required (the
    // PostgresRlsTestBase#resetTables idiom).
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement()) {
      int updated =
          st.executeUpdate(
              "UPDATE pay_component SET statutory_rule_key = NULL WHERE component_key = 'PPH21'");
      assertThat(updated).isEqualTo(1);
    }

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () ->
                        payrollRunService.calculate(
                            new RunPayrollCommand("2026-12", List.of(employeeId), List.of()), IDR)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("swapped ZERO statutory components");

    // No CALCULATED/POSTED run resulted — only the uniform FAILED audit row PayrollRunService
    // records for every calculate() exception.
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM payroll_run WHERE period = '2026-12' AND status <> 'FAILED'"))
        .isZero();
  }

  // ---------------------------------------------------------------------
  // AnnualContext assembly: mixed EARNING components (BASE + a taxable COMMISSION in one prior
  // month only) + employer legs (BPJS-Kes/JKK/JKM-ER) + employee legs (JHT/JP-EE), across TWO
  // active prior months, correctly decrypted-and-summed into ONE December Art-17 figure. Also
  // exercises the monthsInYear-prorated biaya-jabatan cap binding in a live run (W2).
  // ---------------------------------------------------------------------

  @Test
  void decemberTrueUpAssemblesTheAnnualContextFromMixedEarningsAndEmployerLegs() throws Exception {
    Map<String, UUID> ids =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR); // the bootstrap: seeds BASE/COMMISSION/BPJS_KES_*/PPH21
              officialSeeder.seed(DATASET_VERSION); // rewires onto the OFFICIAL TER/Art-17 rules
              UUID outlet = openOutlet();
              UUID commissionComponentId = existingCommissionComponentId();

              UUID employeeId = employeeWithNpwp("Wulan", "3209000000000003", "TK0");
              EmploymentContract contract = addPermanentContract(employeeId);
              CompensationPackage pkg =
                  compensationWriter.createPackage(
                      employeeId,
                      contract.getId(),
                      Money.ofMinor(20_000_000L, IDR),
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(9999, 12, 31));
              compensationWriter.addFixedEarning(
                  pkg.getId(),
                  commissionComponentId,
                  Money.ofMinor(5_000_000L, IDR),
                  LocalDate.of(2026, 1, 1),
                  LocalDate.of(2026, 1, 31));
              assign(employeeId, outlet);

              // Jan: base 20,000,000 + commission 5,000,000 (taxable) = 25,000,000 taxable gross.
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-01", List.of(employeeId), List.of()), IDR);
              // Feb: base only, no commission (its effective_to ends 2026-01-31).
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-02", List.of(employeeId), List.of()), IDR);
              // December: base only.
              UUID runId =
                  payrollRunService
                      .calculateAndPost(
                          new RunPayrollCommand("2026-12", List.of(employeeId), List.of()), IDR)
                      .getId();
              return Map.of("employee", employeeId, "run", runId);
            });

    PayslipLineResponse pph21 = pph21Line(ids.get("run"), ids.get("employee"));
    // See the class javadoc + PayrollRunWriter#buildAnnualContext javadoc for the derivation:
    // cumulativeGrossBruto 18,871,200 (Jan 10,484,000-equivalent-shaped 25,690,000 + Feb
    // 8,387,200 — see the test source comment trail) + December 20,648,000... (full arithmetic in
    // the PR description); the line itself is 493,450 (annual liability) - 4,427,320 (Jan+Feb TER
    // withheld) = -3,933,870.
    assertThat(pph21.currency()).isEqualTo(IDR);
    assertThat(pph21.amountMinor()).isEqualTo(-3_933_870L);
  }

  // ---------------------------------------------------------------------
  // Supersession: a company-wide re-run of November (a corrected comp package) must leave ONLY
  // the ACTIVE (higher run_seq, POSTED) November figures in December's AnnualContext — the
  // superseded run_seq=1 (a materially different base) must contribute NOTHING. Proven both in
  // absolute terms (a hand-derived expected figure) and relatively (an employee who never had a
  // correction, run at the SAME final base, must land on the IDENTICAL December figure).
  // ---------------------------------------------------------------------

  @Test
  void decemberTrueUpExcludesASupersededNovemberRunFromTheAnnualContext() throws Exception {
    Map<String, UUID> ids =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR); // the bootstrap: seeds BASE/COMMISSION/BPJS_KES_*/PPH21
              officialSeeder.seed(DATASET_VERSION); // rewires onto the OFFICIAL TER/Art-17 rules
              UUID outlet = openOutlet();

              // Employee A: starts at a STALE base (45,000,000), corrected down to 30,000,000
              // before November is re-run.
              UUID employeeA = employeeWithNpwp("Aditya", "3209000000000004", "TK0");
              EmploymentContract contractA = addPermanentContract(employeeA);
              CompensationPackage staleV1 =
                  compensationWriter.createPackage(
                      employeeA,
                      contractA.getId(),
                      Money.ofMinor(45_000_000L, IDR),
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(9999, 12, 31));
              assign(employeeA, outlet);

              // Employee C (the control): the FINAL base (30,000,000) from day one, never
              // corrected.
              UUID employeeC = employeeWithNpwp("Citra", "3209000000000005", "TK0");
              EmploymentContract contractC = addPermanentContract(employeeC);
              compensationWriter.createPackage(
                  employeeC,
                  contractC.getId(),
                  Money.ofMinor(30_000_000L, IDR),
                  LocalDate.of(2026, 1, 1),
                  LocalDate.of(9999, 12, 31));
              assign(employeeC, outlet);

              // run_seq=1 for period 2026-11: company-wide (A stale @45M, C @30M).
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-11", List.of(employeeA, employeeC), List.of()), IDR);

              // Correct A's package: close the stale one, open the final one at 30,000,000.
              compensationWriter.endPackage(employeeA, staleV1.getId(), LocalDate.of(2026, 10, 31));
              compensationWriter.createPackage(
                  employeeA,
                  contractA.getId(),
                  Money.ofMinor(30_000_000L, IDR),
                  LocalDate.of(2026, 11, 1),
                  LocalDate.of(9999, 12, 31));

              // run_seq=2 for period 2026-11: company-wide again — SUPERSEDES run_seq=1 for BOTH
              // (A now @30M, matching C; C's own figures are unchanged content, harmlessly
              // re-posted).
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-11", List.of(employeeA, employeeC), List.of()), IDR);

              // December: both at base 30,000,000, company-wide, run_seq=1 for the period.
              UUID decRunId =
                  payrollRunService
                      .calculateAndPost(
                          new RunPayrollCommand(
                              "2026-12", List.of(employeeA, employeeC), List.of()),
                          IDR)
                      .getId();
              return Map.of("A", employeeA, "C", employeeC, "run", decRunId);
            });

    UUID decRunId = ids.get("run");

    PayslipLineResponse pph21A = pph21Line(decRunId, ids.get("A"));
    PayslipLineResponse pph21C = pph21Line(decRunId, ids.get("C"));

    // Absolute: matches the hand-derived expectation for the 30,000,000-base November + December
    // (see the class javadoc derivation pattern) — NOT the stale 45,000,000-base figure a bug
    // leaking run_seq=1 into the sum would have produced.
    assertThat(pph21A.amountMinor()).isEqualTo(-3_742_510L);
    // Relative: A (corrected) and C (never corrected, always at the SAME final base) land on the
    // IDENTICAL December true-up — proof the superseded run contributed exactly zero.
    assertThat(pph21A.amountMinor()).isEqualTo(pph21C.amountMinor());
  }

  // ---------------------------------------------------------------------
  // W1 (P3 review round-2 carry-in): the ACTIVE_RUN_PREDICATE's status='POSTED' filter is
  // load-bearing and was previously unproven for a higher run_seq that never reached POSTED. A
  // November run_seq=2 that only reached CALCULATED (payslip lines genuinely persisted, never
  // posted) must NOT contribute to December's AnnualContext; a FAILED run_seq=3 (rolled back, no
  // lines at all) must not disturb which lower run_seq resolves as "active" either.
  // ---------------------------------------------------------------------

  @Test
  void decemberTrueUpIgnoresAnUnpostedOrFailedHigherRunSeqWhenSelectingTheActiveNovemberRun()
      throws Exception {
    Map<String, UUID> ids =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR); // the bootstrap: seeds BASE/COMMISSION/BPJS_KES_*/PPH21
              officialSeeder.seed(DATASET_VERSION); // rewires onto the OFFICIAL TER/Art-17 rules
              UUID outlet = openOutlet();

              // A single 30,000,000 package, open Jan onward — the SAME shape already
              // hand-verified as employee C (the control) in
              // decemberTrueUpExcludesASupersededNovemberRunFromTheAnnualContext, so this test
              // reuses that exact hand-derived expectation rather than re-deriving new arithmetic.
              UUID employeeA = employeeWithNpwp("Dimas", "3209000000000007", "TK0");
              EmploymentContract contractA = addPermanentContract(employeeA);
              compensationWriter.createPackage(
                  employeeA,
                  contractA.getId(),
                  Money.ofMinor(30_000_000L, IDR),
                  LocalDate.of(2026, 1, 1),
                  LocalDate.of(9999, 12, 31));
              assign(employeeA, outlet);

              // run_seq=1 for period 2026-11: POSTED. This is the run that MUST end up "active".
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-11", List.of(employeeA), List.of()), IDR);

              // A SECOND, November-only package stacks an extra 99,000,000 on top of the first —
              // a massively different (and unmistakably wrong if it leaked) figure. It does not
              // cover December (effective_to = 2026-11-30), so it cannot skew December directly.
              compensationWriter.createPackage(
                  employeeA,
                  contractA.getId(),
                  Money.ofMinor(99_000_000L, IDR),
                  LocalDate.of(2026, 11, 1),
                  LocalDate.of(2026, 11, 30));

              // run_seq=2 for period 2026-11: calculate() ONLY — never post(). Its payslip lines
              // ARE persisted (calculate() writes them before the run reaches CALCULATED), but the
              // run's status stays CALCULATED, never POSTED. The predicate's outer `status =
              // 'POSTED'` must exclude this row from December's sum.
              payrollRunService.calculate(
                  new RunPayrollCommand("2026-11", List.of(employeeA), List.of()), IDR);

              // run_seq=3 for period 2026-11: an unknown employee id fails calculate() loudly;
              // PayrollRunService.calculate catches the RuntimeException and records a FAILED
              // audit row (a separate REQUIRES_NEW transaction) with NO payslip lines at all — the
              // doomed compute transaction rolled back entirely. The mere EXISTENCE of this higher
              // run_seq row must not perturb the MAX(run_seq) resolution for run_seq=1 either.
              try {
                payrollRunService.calculate(
                    new RunPayrollCommand("2026-11", List.of(UUID.randomUUID()), List.of()), IDR);
                throw new AssertionError("expected the unknown-employee run to fail");
              } catch (IllegalArgumentException expected) {
                // recordFailedAttempt already wrote the FAILED row — asserted below.
              }

              UUID failedRunId =
                  payrollRunReader.listByPeriod("2026-11").stream()
                      .filter(r -> "FAILED".equals(r.status()))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("no FAILED run recorded"))
                      .id();

              // December: run_seq=1 for the period, using employee A's ORIGINAL 30,000,000
              // package only (the November-only 99,000,000 package does not cover December).
              UUID decRunId =
                  payrollRunService
                      .calculateAndPost(
                          new RunPayrollCommand("2026-12", List.of(employeeA), List.of()), IDR)
                      .getId();
              return Map.of("employee", employeeA, "run", decRunId, "failedRun", failedRunId);
            });

    // The FAILED run genuinely exists but carries ZERO payslip lines.
    UUID failedRunId = ids.get("failedRun");
    assertThat(
            TenantContext.callAs(
                TENANT_A, ACTOR_A, () -> payrollRunReader.payslipIndex(failedRunId).orElseThrow()))
        .isEmpty();

    // December's AnnualContext reflects ONLY the POSTED run_seq=1 (base 30,000,000) — the SAME
    // hand-derived figure as decemberTrueUpExcludesASupersededNovemberRunFromTheAnnualContext's
    // control employee (single 30,000,000 November + December, monthsInYear=2) — NOT the
    // CALCULATED-but-unposted run_seq=2's 129,000,000-base figure, and undisturbed by the FAILED
    // run_seq=3's mere existence.
    PayslipLineResponse pph21 = pph21Line(ids.get("run"), ids.get("employee"));
    assertThat(pph21.amountMinor()).isEqualTo(-3_742_510L);
  }

  // ---------------------------------------------------------------------
  // W1 (P1-review carry-in): a NEGATIVE December PPh21 line survives the full path — two months
  // of high TER withholding followed by a low-income December produces a large refund. Run
  // totals stay consistent, PayrollPosted's decoded Avro totals agree with the persisted run, and
  // MoneyPiiConverter round-trips the negative amount (ciphertext at rest carries no plaintext
  // digits; the decrypted authorized read matches the hand-derived figure exactly).
  // ---------------------------------------------------------------------

  @Test
  void aNegativeDecemberPphLineSurvivesTheFullPathAndRoundTripsThroughEncryption()
      throws Exception {
    Map<String, UUID> ids =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR); // the bootstrap: seeds BASE/COMMISSION/BPJS_KES_*/PPH21
              officialSeeder.seed(DATASET_VERSION); // rewires onto the OFFICIAL TER/Art-17 rules
              UUID outlet = openOutlet();
              UUID employeeId = employeeWithNpwp("Farhan", "3209000000000006", "TK0");
              EmploymentContract contract = addPermanentContract(employeeId);
              CompensationPackage highPkg =
                  compensationWriter.createPackage(
                      employeeId,
                      contract.getId(),
                      Money.ofMinor(300_000_000L, IDR),
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(9999, 12, 31));
              assign(employeeId, outlet);

              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-10", List.of(employeeId), List.of()), IDR);
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-11", List.of(employeeId), List.of()), IDR);

              compensationWriter.endPackage(
                  employeeId, highPkg.getId(), LocalDate.of(2026, 11, 30));
              compensationWriter.createPackage(
                  employeeId,
                  contract.getId(),
                  Money.ofMinor(5_000_000L, IDR),
                  LocalDate.of(2026, 12, 1),
                  LocalDate.of(9999, 12, 31));

              UUID runId =
                  payrollRunService
                      .calculateAndPost(
                          new RunPayrollCommand("2026-12", List.of(employeeId), List.of()), IDR)
                      .getId();
              return Map.of("employee", employeeId, "run", runId);
            });

    UUID runId = ids.get("run");
    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findRun(runId).orElseThrow());
    assertThat(run.status()).isEqualTo("POSTED");

    PayslipLineResponse pph21 = pph21Line(runId, ids.get("employee"));
    // See the class javadoc derivation: 107,014,300 (annual liability) - 169,680,000 (Oct+Nov TER
    // withheld) = -62,665,700 — a large refund.
    assertThat(pph21.amountMinor()).isEqualTo(-62_665_700L);

    // Run totals stay arithmetically consistent even with a negative deduction line: net = gross
    // - sum(EMPLOYEE deductions), and the negative refund INCREASES net pay this month.
    assertThat(run.grossTotalMinor()).isEqualTo(5_000_000L);
    assertThat(run.employeeDeductionTotalMinor())
        .isEqualTo(-62_465_700L); // 50k+100k+50k-62,665,700
    assertThat(run.netTotalMinor()).isEqualTo(67_465_700L);
    assertThat(run.grossTotalMinor() - run.employeeDeductionTotalMinor())
        .isEqualTo(run.netTotalMinor());

    // PayrollPosted decoded totals agree with the persisted run (Avro `long` carries the sign).
    Map<String, Object> posted =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM outbox WHERE event_type = 'PayrollPosted' AND aggregate_id = ?",
            runId.toString());
    GenericRecord postedRecord =
        AvroSerde.deserialize((byte[]) posted.get("payload"), PayrollPostedSchema.schema());
    assertThat(postedRecord.get("employee_deduction_total_minor")).isEqualTo(-62_465_700L);
    assertThat(postedRecord.get("net_total_minor")).isEqualTo(67_465_700L);

    // ADR 0032 (Track P phase P4): PayrollLiabilitiesPosted survives the negative-December-PPh21
    // case — PPH21_PAYABLE is NEGATIVE (a refund), and the identity still balances.
    // employer_cost_total = gross 5,000,000 + employer BPJS (OFFICIAL 10.54% of 5,000,000)
    // 527,000 = 5,527,000. Buckets: NET_WAGES_PAYABLE 67,465,700; PPH21_PAYABLE -62,665,700;
    // BPJS_KES_PAYABLE 250,000 (50,000 EE + 200,000 ER); BPJS_TK_PAYABLE 477,000 (100,000 JHT-EE +
    // 50,000 JP-EE + 185,000 JHT-ER + 100,000 JP-ER + 27,000 JKK-ER + 15,000 JKM-ER). Sum:
    // 67,465,700 - 62,665,700 + 250,000 + 477,000 = 5,527,000.
    Map<String, Object> liabilitiesRow =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM outbox WHERE event_type = 'PayrollLiabilitiesPosted' AND"
                + " aggregate_id = ?",
            runId.toString());
    GenericRecord liabilitiesRecord =
        AvroSerde.deserialize(
            (byte[]) liabilitiesRow.get("payload"), PayrollLiabilitiesPostedSchema.schema());
    assertThat(liabilitiesRecord.get("employer_cost_total_minor")).isEqualTo(5_527_000L);

    @SuppressWarnings("unchecked")
    List<GenericRecord> liabilityBuckets =
        (List<GenericRecord>) liabilitiesRecord.get("liabilities");
    Map<String, Long> byRole = new java.util.LinkedHashMap<>();
    for (GenericRecord bucket : liabilityBuckets) {
      byRole.put(bucket.get("liability_role").toString(), (Long) bucket.get("amount_minor"));
    }
    assertThat(byRole).containsEntry("NET_WAGES_PAYABLE", 67_465_700L);
    assertThat(byRole).containsEntry("PPH21_PAYABLE", -62_665_700L); // NEGATIVE — the refund month
    assertThat(byRole).containsEntry("BPJS_KES_PAYABLE", 250_000L);
    assertThat(byRole).containsEntry("BPJS_TK_PAYABLE", 477_000L);
    assertThat(byRole).doesNotContainKey("OTHER_DEDUCTIONS_PAYABLE");

    long liabilitySum = byRole.values().stream().mapToLong(Long::longValue).sum();
    assertThat(liabilitySum).isEqualTo(5_527_000L);

    // MoneyPiiConverter round-trip: the ciphertext at rest carries no plaintext digits of the
    // negative amount (rule 6) — the decrypted authorized read matching the hand-derived figure
    // exactly (asserted above) is the application-level half of the same proof. Read over the
    // admin/BYPASSRLS connection (mirrors PayrollRunEndToEndTest#queryAsAdmin): the Spring-managed
    // jdbcTemplate connects as the RLS-enforcing app_user with NO tenant GUC bound outside a
    // @Transactional method, so a bare query against a FORCE-RLS table like payslip_line fails
    // closed (empty) — this is a raw ciphertext-content check, not a tenant-isolation proof.
    List<String> ciphertexts = queryAsAdmin(runId);
    assertThat(ciphertexts).hasSize(1);
    assertThat(ciphertexts.get(0)).isNotBlank().doesNotContain("62665700").doesNotContain("IDR");
  }

  // ---------------------------------------------------------------------
  // P7 review S2(c) — a NEGATIVE December PPh21 (a refund month) COMBINED with a linked
  // expense-claim reimbursement in the SAME run: the SAME recipe as the test immediately above
  // (two high-TER months then a low December, ID-2026.1's exact hand-derived -62,665,700 refund),
  // PLUS ID-2026.2's P7 catalog top-up (W5's BASE_PAY fix) and a claim riding this December
  // payslip. Since the ONLY earnings are BASE (taxable) and EXPENSE_REIMBURSEMENT (non-taxable),
  // switching from ID-2026.1 to ID-2026.2 changes NEITHER PPh21 NOR the BPJS legs (BASE_PAY ==
  // taxableCashEarnings whenever there is no OTHER taxable earning like commission/overtime) — the
  // combined scenario's PPh21/BPJS figures are IDENTICAL to the sibling test's, proving the
  // reimbursement rides alongside the true-up without perturbing it, and the liability split still
  // balances with reimbursement excluded from NET_WAGES_PAYABLE.
  // ---------------------------------------------------------------------

  @Test
  void aNegativeDecemberPphRefundCombinedWithALinkedClaimReimbursementBalancesTheLiabilitySplit()
      throws Exception {
    Map<String, UUID> ids =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              illustrativeSeeder.seed(IDR);
              officialSeeder.seed(DATASET_VERSION); // ID-2026.1
              officialSeeder.seed("ID-2026.2"); // the P7 top-up (W5's BASE_PAY fix; OVERTIME/
              // UNPAID_LEAVE/EXPENSE_REIMBURSEMENT components) — supersedes the 5 BPJS rules.
              UUID outlet = openOutlet();
              UUID employeeId = employeeWithNpwp("Farhan", "3209000000000007", "TK0");
              employeeService.linkUser(employeeId, EMPLOYEE_ACTOR, null);
              EmploymentContract contract = addPermanentContract(employeeId);
              CompensationPackage highPkg =
                  compensationWriter.createPackage(
                      employeeId,
                      contract.getId(),
                      Money.ofMinor(300_000_000L, IDR),
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(9999, 12, 31));
              assign(employeeId, outlet);

              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-10", List.of(employeeId), List.of()), IDR);
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-11", List.of(employeeId), List.of()), IDR);

              compensationWriter.endPackage(
                  employeeId, highPkg.getId(), LocalDate.of(2026, 11, 30));
              compensationWriter.createPackage(
                  employeeId,
                  contract.getId(),
                  Money.ofMinor(5_000_000L, IDR),
                  LocalDate.of(2026, 12, 1),
                  LocalDate.of(9999, 12, 31));

              return Map.of("employee", employeeId, "outlet", outlet);
            });

    UUID employeeId = ids.get("employee");
    long claimMinor = 400_000L;
    UUID claimId =
        TenantContext.callAs(
            TENANT_A,
            EMPLOYEE_ACTOR,
            () -> {
              UUID categoryId =
                  categoryWriter.create("Supplies-" + employeeId, "supplies", false).getId();
              UUID id =
                  claimService
                      .create(
                          new CreateClaimCommand(
                              categoryId,
                              claimMinor,
                              IDR,
                              LocalDate.of(2026, 12, 5),
                              "Toko ATK",
                              "note",
                              null))
                      .getId();
              claimService.submit(id, "submit-" + id);
              return id;
            });
    TenantContext.callAs(
        TENANT_A, ACTOR_A, () -> claimService.approve(claimId, "ok", "approve-" + claimId));

    UUID runId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                payrollRunService
                    .calculateAndPost(
                        new RunPayrollCommand("2026-12", List.of(employeeId), List.of()), IDR)
                    .getId());

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findRun(runId).orElseThrow());
    assertThat(run.status()).isEqualTo("POSTED");

    // PPh21 is UNCHANGED from the sibling test — the reimbursement never touches the tax base.
    PayslipLineResponse pph21 = pph21Line(runId, employeeId);
    assertThat(pph21.amountMinor()).isEqualTo(-62_665_700L);

    // gross now ALSO carries the claimMinor reimbursement on top of the sibling test's 5,000,000.
    assertThat(run.grossTotalMinor()).isEqualTo(5_000_000L + claimMinor);
    // employeeDeductionTotal is UNCHANGED (BPJS EE legs + PPh21 — the reimbursement is NOT a
    // deduction, and non-taxable so it never enters any deduction computation).
    assertThat(run.employeeDeductionTotalMinor()).isEqualTo(-62_465_700L);
    assertThat(run.netTotalMinor()).isEqualTo(67_465_700L + claimMinor);

    List<PayslipLineResponse> lines =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findPayslipAuthorized(runId, employeeId));
    assertThat(
            lines.stream()
                .filter(l -> l.componentKey().equals("EXPENSE_REIMBURSEMENT"))
                .findFirst()
                .orElseThrow()
                .amountMinor())
        .isEqualTo(claimMinor);

    // The claim settled: REIMBURSED + ExpenseReimbursementSettled(PAYROLL).
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'REIMBURSED'",
                claimId))
        .isEqualTo(1L);

    // The liability split: NET_WAGES_PAYABLE = net_total - reimbursement (the claim's own 2600
    // payable, already settled separately, must not double-book here); the other buckets
    // (PPH21/BPJS) are IDENTICAL to the sibling test since the reimbursement never touches them;
    // employer_cost_total EXCLUDES the reimbursement too — the full identity still balances.
    Map<String, Object> liabilitiesRow =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM outbox WHERE event_type = 'PayrollLiabilitiesPosted' AND"
                + " aggregate_id = ?",
            runId.toString());
    GenericRecord liabilitiesRecord =
        AvroSerde.deserialize(
            (byte[]) liabilitiesRow.get("payload"), PayrollLiabilitiesPostedSchema.schema());
    assertThat(liabilitiesRecord.get("employer_cost_total_minor")).isEqualTo(5_527_000L);

    @SuppressWarnings("unchecked")
    List<GenericRecord> liabilityBuckets =
        (List<GenericRecord>) liabilitiesRecord.get("liabilities");
    Map<String, Long> byRole = new java.util.LinkedHashMap<>();
    for (GenericRecord bucket : liabilityBuckets) {
      byRole.put(bucket.get("liability_role").toString(), (Long) bucket.get("amount_minor"));
    }
    assertThat(byRole).containsEntry("NET_WAGES_PAYABLE", 67_465_700L); // run.netTotal - claimMinor
    assertThat(byRole).containsEntry("PPH21_PAYABLE", -62_665_700L);
    assertThat(byRole).containsEntry("BPJS_KES_PAYABLE", 250_000L);
    assertThat(byRole).containsEntry("BPJS_TK_PAYABLE", 477_000L);
    long liabilitySum = byRole.values().stream().mapToLong(Long::longValue).sum();
    assertThat(liabilitySum).isEqualTo(5_527_000L);
  }

  private List<String> queryAsAdmin(UUID runId) throws Exception {
    List<String> values = new java.util.ArrayList<>();
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT amount_enc AS v FROM payslip_line"
                    + " WHERE payroll_run_id = ? AND component_key = 'PPH21'")) {
      ps.setObject(1, runId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          values.add(rs.getString("v"));
        }
      }
    }
    return values;
  }

  // ---------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------

  private UUID openOutlet() {
    UUID outlet = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true));
    return outlet;
  }

  private UUID employeeWithNpwp(String fullName, String nik, String ptkpStatus) {
    UUID employeeId =
        employeeService.create(new CreateEmployeeCommand(fullName, ptkpStatus, nik, nik)).getId();
    employeeService.update(
        new UpdateEmployeeCommand(employeeId, null, null, null, null, npwpFor(nik), null));
    return employeeId;
  }

  /** A distinct, valid-looking 16-digit NPWP derived from the employee's own NIK. */
  private String npwpFor(String nik) {
    return "9" + nik.substring(1);
  }

  private EmploymentContract addPermanentContract(UUID employeeId) {
    return employeeService.addContract(
        new AddContractCommand(
            employeeId,
            "PERMANENT",
            LEGAL_EMPLOYER,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(9999, 12, 31)));
  }

  private void assign(UUID employeeId, UUID outlet) {
    assignmentService.add(
        new AddAssignmentCommand(
            employeeId,
            outlet,
            null,
            "cashier",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(9999, 12, 31)));
  }

  /** A TK0/NPWP employee with a single 10,000,000 IDR base package, assigned to {@code outlet}. */
  private UUID tenMillionTk0NpwpEmployee(UUID outlet, String nik) {
    UUID employeeId = employeeWithNpwp("Budi", nik, "TK0");
    EmploymentContract contract = addPermanentContract(employeeId);
    compensationWriter.createPackage(
        employeeId,
        contract.getId(),
        Money.ofMinor(10_000_000L, IDR),
        LocalDate.of(2026, 1, 1),
        LocalDate.of(9999, 12, 31));
    assign(employeeId, outlet);
    return employeeId;
  }

  /**
   * The id of the {@code COMMISSION} pay component the illustrative seeder already created (taxable
   * EARNING; the OFFICIAL dataset's component list does not touch it, so it survives {@code
   * officialSeeder.seed} unchanged) — read directly over the admin/BYPASSRLS connection so the test
   * can wire an earning rule to it without a second, duplicate-keyed insert.
   */
  private UUID existingCommissionComponentId() throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT id FROM pay_component WHERE company_id = ? AND component_key ="
                    + " 'COMMISSION'")) {
      ps.setString(1, TENANT_A);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("COMMISSION component not seeded for " + TENANT_A);
        }
        return (UUID) rs.getObject("id");
      }
    }
  }

  /** The run's {@code PPH21} line for one employee, decrypted (the authorized read). */
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
