package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import id.co.nativeapp.employee.employee.dto.AddContractCommand;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.expense.domain.ClaimStateException;
import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.dto.CreateClaimCommand;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryWriter;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.employee.expense.service.ExpenseClaimWriter;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.employee.payroll.domain.PayComponentBearer;
import id.co.nativeapp.employee.payroll.domain.PayComponentKind;
import id.co.nativeapp.employee.payroll.domain.PayslipLine;
import id.co.nativeapp.employee.payroll.dto.RunPayrollCommand;
import id.co.nativeapp.employee.payroll.repository.PayslipLineRepository;
import id.co.nativeapp.employee.payroll.service.CompensationWriter;
import id.co.nativeapp.employee.payroll.service.IllustrativeStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.PayrollRunService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-PostgreSQL proofs of the payroll↔expense-claim seam (ADR 0030 §6, Track E phase E5): the
 * version-bump race invariant BOTH directions (pay-vs-link and link-vs-pay), a genuine concurrent
 * DIRECT-pay race (E4 review W2, no dedicated Testcontainers proof existed before this phase), and
 * {@code releaseForPeriod}'s release predicates — including the E5-transitional
 * "POSTED-but-still-APPROVED" case, the W3 same-cycle supersession fix (the corrective run's OWN
 * {@code calculate()} releases and re-links a superseded claim, never a run later), and the W2
 * period-agnostic non-POSTED branch (a claim stranded on an abandoned run of one period is freed by
 * a {@code calculate()} for ANY other period) — all documented on {@code ExpenseClaimPayrollLinker}
 * (E5 review findings).
 */
@SpringBootTest
class ExpenseClaimPayrollLinkerIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "hr-admin-a@example.co.id";
  private static final String CLAIMANT_ACTOR = "aaaaaaaa-1111-1111-1111-111111111111";
  private static final String MANAGER_ACTOR = "manager-not-linked-to-any-employee";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String IDR = "IDR";
  private static final long BASE_MINOR = 20_000_000L;

  @Autowired private IllustrativeStatutorySeedWriter seeder;
  @Autowired private EmployeeService employeeService;
  @Autowired private CompensationWriter compensationWriter;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private AssignmentService assignmentService;
  @Autowired private ExpenseCategoryWriter categoryWriter;
  @Autowired private ExpenseClaimService claimService;
  @Autowired private ExpenseClaimWriter claimWriter;
  @Autowired private TestPayslipLineWriter testPayslipLineWriter;
  @Autowired private PayrollRunService payrollRunService;

  private record ClaimSetup(UUID employeeId, UUID claimId) {}

  /**
   * A test-only {@code REQUIRES_NEW} unit of work — mirrors every production {@code *Writer} in
   * this codebase (the {@code ExpenseCategoryWriter}/{@code ExpenseClaimWriter} idiom) so its
   * {@code save} genuinely goes through the Spring proxy that engages BOTH the transaction advisor
   * AND {@code RlsAutoApplyAspect}. A bare {@code @Autowired PayslipLineRepository} called directly
   * from test code (no enclosing {@code @Transactional} bean) does NOT get this treatment — Spring
   * Data's repository proxy factory wires its OWN transaction interceptor without going through the
   * application context's registered {@code @Aspect} beans, so the RLS GUC is never set and every
   * write trips {@code WITH CHECK} (empirically confirmed while building this test: a direct {@code
   * repository.save(...)} call failed RLS on both {@code expense_claim} and {@code payslip_line}).
   * Track P Phase P7 will supersede this with a real production writer that emits this line from
   * the gross-to-net engine; until then, this is the only correct way to seed one from a test.
   */
  @TestConfiguration
  static class TestPayslipLineWriterConfig {
    @Bean
    TestPayslipLineWriter testPayslipLineWriter(PayslipLineRepository repository) {
      return new TestPayslipLineWriter(repository);
    }
  }

  static class TestPayslipLineWriter {
    private final PayslipLineRepository repository;

    TestPayslipLineWriter(PayslipLineRepository repository) {
      this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    PayslipLine save(PayslipLine line) {
      return repository.save(line);
    }
  }

  // ---------------------------------------------------------------------
  // (a) pay-vs-link race: the linker links FIRST; a STALE payDirect flush (built from a
  // pre-link snapshot) must lose the optimistic-lock race — the ADR 0030 §6 BINDING invariant.
  // ---------------------------------------------------------------------

  @Test
  void aStalePayDirectFlushLosesOptimisticLockAfterTheLinkerClaimsTheLink() throws Exception {
    ClaimSetup setup = setUpEmployeeWithApprovedClaim("3201111111111111", "1111222233334444");

    // A "pre-link" snapshot — exactly what a manager's in-flight POST /pay would have loaded
    // before the payroll engine's linkForRun ran concurrently. The AGGREGATE'S OWN guard
    // (ExpenseClaim#payDirect) reads this in-memory copy, which still shows
    // reimbursement_run_id = NULL, so it PASSES — only the DB's optimistic @Version check can
    // stop the stale flush from here on.
    ExpenseClaim stale =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> claimWriter.findByIdForReplay(setup.claimId()).orElseThrow());
    assertThat(stale.getReimbursementRunId()).isNull();
    long staleVersion = stale.getVersion();
    stale.payDirect(Instant.parse("2026-07-25T10:00:00Z")); // passes the aggregate's own guard

    // The payroll engine links it to a run — a genuinely separate, already-COMMITTED transaction.
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> payrollRunService.calculate(runCommand("2026-07", setup.employeeId()), IDR));

    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND reimbursement_run_id IS NOT"
                    + " NULL",
                setup.claimId()))
        .isEqualTo(1L);

    // The stale flush: replays the SHAPE of the optimistic-locked UPDATE
    // ExpenseClaimWriter#payDirect's claimRepository.save(claim) issues on flush — "WHERE id = ?
    // AND version = ?" using the PRE-LINK version (a deterministic simulation of what a real stale
    // JPA save would attempt at the SQL level; see ExpenseClaimWriterTest for a mock-level proof
    // that payDirect() itself never catches/swallows the resulting optimistic-lock exception).
    // Without the linker's version bump (ADR 0030 §6 BINDING) this WHERE clause would still match
    // today and silently clobber the link back to NULL (a double payment); with the bump, it
    // matches ZERO rows.
    int staleUpdateRowsAffected =
        attemptStaleVersionedPayDirectUpdate(setup.claimId(), staleVersion);
    assertThat(staleUpdateRowsAffected).isZero();

    // The claim stays linked and APPROVED — the stale write never took effect.
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'APPROVED' AND"
                    + " reimbursement_run_id IS NOT NULL",
                setup.claimId()))
        .isEqualTo(1L);
    // No settlement was ever recorded for the stale attempt.
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM outbox WHERE event_type = 'ExpenseReimbursementSettled' AND"
                    + " aggregate_id = ?",
                setup.claimId().toString()))
        .isZero();
  }

  // ---------------------------------------------------------------------
  // (b) link-vs-pay: payDirect commits FIRST; the linker's conditional UPDATE then matches 0
  // rows (the claim is no longer APPROVED+unlinked) — no exception, just a clean no-op.
  // ---------------------------------------------------------------------

  @Test
  void whenPayDirectCommitsFirstTheLinkersConditionalUpdateMatchesZeroRows() throws Exception {
    ClaimSetup setup = setUpEmployeeWithApprovedClaim("3202222222222222", "2222333344445555");

    TenantContext.callAs(
        TENANT_A, MANAGER_ACTOR, () -> claimService.payDirect(setup.claimId(), "pay-key"));
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'REIMBURSED' AND"
                    + " reimbursement_run_id IS NULL",
                setup.claimId()))
        .isEqualTo(1L);

    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> payrollRunService.calculate(runCommand("2026-07", setup.employeeId()), IDR));

    // Untouched: still REIMBURSED (DIRECT), never linked to the run.
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'REIMBURSED' AND"
                    + " reimbursement_run_id IS NULL",
                setup.claimId()))
        .isEqualTo(1L);
  }

  // ---------------------------------------------------------------------
  // (c) two concurrent DIRECT pays, DIFFERENT idempotency keys (E4 review W2) — no idempotency-key
  // short-circuit is available (the keys differ), so exactly-once settlement rests ENTIRELY on the
  // claim's own concurrency guards: the optimistic @Version lock if the loser's read raced ahead of
  // the winner's commit (ObjectOptimisticLockingFailureException), or the aggregate's own status
  // guard if the loser's read landed AFTER the winner's commit (ClaimStateException, "not APPROVED
  // any more"). Genuine thread timing decides WHICH guard catches the loser — both map to 409
  // (EmployeeApiAdvice) and both are correct outcomes of the SAME invariant, so the test accepts
  // either rather than asserting a specific interleaving (ENGINEERING-STANDARDS §3.3, no flaky
  // ordering assumptions).
  // ---------------------------------------------------------------------

  @Test
  void concurrentDirectPaysWithDifferentKeysYieldExactlyOneWinnerAndOneConflictLoser()
      throws Exception {
    ClaimSetup setup = setUpEmployeeWithApprovedClaim("3203333333333333", "3333444455556666");

    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<ExpenseClaim> payA =
        () ->
            TenantContext.callAs(
                TENANT_A,
                MANAGER_ACTOR,
                () -> {
                  barrier.await();
                  return claimService.payDirect(setup.claimId(), "pay-key-a");
                });
    Callable<ExpenseClaim> payB =
        () ->
            TenantContext.callAs(
                TENANT_A,
                MANAGER_ACTOR,
                () -> {
                  barrier.await();
                  return claimService.payDirect(setup.claimId(), "pay-key-b");
                });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    int successes = 0;
    int conflicts = 0;
    try {
      Future<ExpenseClaim> futureA = pool.submit(payA);
      Future<ExpenseClaim> futureB = pool.submit(payB);
      for (Future<ExpenseClaim> future : List.of(futureA, futureB)) {
        try {
          ExpenseClaim result = future.get();
          assertThat(result.getStatus()).isEqualTo(ClaimStatus.REIMBURSED);
          successes++;
        } catch (ExecutionException e) {
          // Either guard is a correct "lost the race" outcome — see the class-level comment above.
          assertThat(e.getCause())
              .isInstanceOfAny(
                  ObjectOptimisticLockingFailureException.class, ClaimStateException.class);
          conflicts++;
        }
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(successes).isEqualTo(1);
    assertThat(conflicts).isEqualTo(1);
    // Exactly ONE settlement — the loser's transaction rolled back whole (no partial state).
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM outbox WHERE event_type = 'ExpenseReimbursementSettled' AND"
                    + " aggregate_id = ?",
                setup.claimId().toString()))
        .isEqualTo(1L);
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'REIMBURSED'",
                setup.claimId()))
        .isEqualTo(1L);
  }

  // ---------------------------------------------------------------------
  // (d) releaseForPeriod's release predicates: abandoned non-POSTED run (period-agnostic, W2),
  // E5-transitional POSTED-but-still-APPROVED, and same-cycle supersession (W3).
  // ---------------------------------------------------------------------

  @Test
  void releaseForPeriodFreesAClaimLinkedToAnAbandonedNonPostedRunForRelinking() throws Exception {
    ClaimSetup setup = setUpEmployeeWithApprovedClaim("3204444444444444", "4444555566667777");

    UUID run1Id =
        TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> payrollRunService.calculate(runCommand("2026-07", setup.employeeId()), IDR))
            .getId();
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND reimbursement_run_id = ?",
                setup.claimId(),
                run1Id))
        .isEqualTo(1L);

    // A second calculate() for the SAME period, run1 abandoned (never posted) — releases + relinks.
    UUID run2Id =
        TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> payrollRunService.calculate(runCommand("2026-07", setup.employeeId()), IDR))
            .getId();

    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'APPROVED' AND"
                    + " reimbursement_run_id = ?",
                setup.claimId(),
                run2Id))
        .isEqualTo(1L);
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND reimbursement_run_id = ?",
                setup.claimId(),
                run1Id))
        .isZero();
  }

  @Test
  void releaseForPeriodFreesAnE5TransitionalClaimStillApprovedOnAPostedRun() throws Exception {
    ClaimSetup setup = setUpEmployeeWithApprovedClaim("3205555555555555", "5555666677778888");

    // POSTED, but NO EXPENSE_REIMBURSEMENT payslip line exists yet (pre-Track-P-Phase-P7): the
    // linked claim stays APPROVED — markReimbursedAndEmit's gate never flips it.
    UUID run1Id =
        TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () ->
                    payrollRunService.calculateAndPost(
                        runCommand("2026-07", setup.employeeId()), IDR))
            .getId();
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'APPROVED' AND"
                    + " reimbursement_run_id = ?",
                setup.claimId(),
                run1Id))
        .isEqualTo(1L);
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM outbox WHERE event_type = 'ExpenseReimbursementSettled' AND"
                    + " aggregate_id = ?",
                setup.claimId().toString()))
        .isZero();

    // A re-run for the SAME period releases the transitionally-stuck claim and re-links it.
    UUID run2Id =
        TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> payrollRunService.calculate(runCommand("2026-07", setup.employeeId()), IDR))
            .getId();

    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'APPROVED' AND"
                    + " reimbursement_run_id = ?",
                setup.claimId(),
                run2Id))
        .isEqualTo(1L);
  }

  @Test
  void theCorrectiveRunsOwnCalculateReleasesAndRelinksASupersededReimbursedClaimSameCycle()
      throws Exception {
    // W3 (E5 review, the important fix): supersession release+relink happens in the CORRECTIVE
    // run's OWN calculate() cycle — not a third run later, as the original E5 predicate required
    // (it only matched a run_seq STRICTLY HIGHER than the calculating run's own, which cannot
    // exist yet during that very run's calculate()).
    ClaimSetup setup = setUpEmployeeWithApprovedClaim("3206666666666666", "6666777788889999");

    // run1: simulate Track P Phase P7 by hand-inserting the EXPENSE_REIMBURSEMENT payslip line
    // between calculate() and post() — the run POSTS and the claim is genuinely REIMBURSED.
    UUID run1Id =
        TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> payrollRunService.calculate(runCommand("2026-07", setup.employeeId()), IDR))
            .getId();
    insertExpenseReimbursementPayslipLine(run1Id, setup.employeeId());
    TenantContext.callAs(TENANT_A, ACTOR_A, () -> payrollRunService.post(run1Id));

    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'REIMBURSED' AND"
                    + " reimbursement_run_id = ?",
                setup.claimId(),
                run1Id))
        .isEqualTo(1L);
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM outbox WHERE event_type = 'ExpenseReimbursementSettled' AND"
                    + " aggregate_id = ?",
                setup.claimId().toString()))
        .isEqualTo(1L);

    // run2: a correction for the SAME period. Its OWN calculate() must release run1's claim
    // (run1.run_seq=1 < run2's own run_seq=2 — W3) and re-link it to run2 IN THE SAME CYCLE,
    // BEFORE run2 ever posts — assert this immediately after calculate(), before posting.
    UUID run2Id =
        TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> payrollRunService.calculate(runCommand("2026-07", setup.employeeId()), IDR))
            .getId();
    assertThat(run2Id).isNotEqualTo(run1Id);

    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'APPROVED' AND"
                    + " reimbursement_run_id = ?",
                setup.claimId(),
                run2Id))
        .isEqualTo(1L);
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND reimbursement_run_id = ?",
                setup.claimId(),
                run1Id))
        .isZero();

    // Posting run2 (with the P7-simulated line again present) RE-settles: a SECOND
    // ExpenseReimbursementSettled(PAYROLL) event for the SAME claim_id (finance's per-claim
    // settle-once guard treats the re-emission as a no-op, ADR 0030 §7 — not asserted here, that
    // invariant lives in finance-service).
    insertExpenseReimbursementPayslipLine(run2Id, setup.employeeId());
    TenantContext.callAs(TENANT_A, ACTOR_A, () -> payrollRunService.post(run2Id));

    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'REIMBURSED' AND"
                    + " reimbursement_run_id = ?",
                setup.claimId(),
                run2Id))
        .isEqualTo(1L);
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM outbox WHERE event_type = 'ExpenseReimbursementSettled' AND"
                    + " aggregate_id = ?",
                setup.claimId().toString()))
        .isEqualTo(2L);
  }

  @Test
  void releaseForPeriodFreesAClaimStrandedOnAnAbandonedRunOfADifferentPeriod() throws Exception {
    // W2 (E5 review): the "run not POSTED" release branch is PERIOD-AGNOSTIC. A claim stranded on
    // an abandoned (CALCULATED-but-never-POSTED) run of one period must be freed by a calculate()
    // for ANY OTHER period, not only a re-run of that same stale period.
    ClaimSetup setup = setUpEmployeeWithApprovedClaim("3207777777777777", "7777888899990000");

    UUID mayRunId =
        TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> payrollRunService.calculate(runCommand("2026-05", setup.employeeId()), IDR))
            .getId();
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND reimbursement_run_id = ?",
                setup.claimId(),
                mayRunId))
        .isEqualTo(1L);

    // The May run is abandoned — never posted. A calculate() for a DIFFERENT period (June) must
    // still release+relink the claim.
    UUID juneRunId =
        TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> payrollRunService.calculate(runCommand("2026-06", setup.employeeId()), IDR))
            .getId();

    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'APPROVED' AND"
                    + " reimbursement_run_id = ?",
                setup.claimId(),
                juneRunId))
        .isEqualTo(1L);
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND reimbursement_run_id = ?",
                setup.claimId(),
                mayRunId))
        .isZero();
  }

  // ---------------------------------------------------------------------
  // (e) Concurrent calculate() for TWO DIFFERENT periods, same employee, same claim (Track P Phase
  // P7 review S2) — the claim ends up linked to EXACTLY ONE of the two runs, proven by real
  // concurrent threads (not just reasoned about), and neither calculate() call itself fails.
  // ---------------------------------------------------------------------

  @Test
  void concurrentCalculateForTwoDifferentPeriodsLinksTheSharedClaimToExactlyOneRun()
      throws Exception {
    ClaimSetup setup = setUpEmployeeWithApprovedClaim("3208888888888888", "8888999900001111");

    java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
    java.util.concurrent.Callable<UUID> calculateMay =
        () ->
            TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> {
                  barrier.await();
                  return payrollRunService
                      .calculate(runCommand("2026-05", setup.employeeId()), IDR)
                      .getId();
                });
    java.util.concurrent.Callable<UUID> calculateJune =
        () ->
            TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> {
                  barrier.await();
                  return payrollRunService
                      .calculate(runCommand("2026-06", setup.employeeId()), IDR)
                      .getId();
                });

    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    UUID mayRunId;
    UUID juneRunId;
    try {
      java.util.concurrent.Future<UUID> mayFuture = pool.submit(calculateMay);
      java.util.concurrent.Future<UUID> juneFuture = pool.submit(calculateJune);
      mayRunId = mayFuture.get();
      juneRunId = juneFuture.get();
    } finally {
      pool.shutdownNow();
    }

    assertThat(mayRunId).isNotEqualTo(juneRunId);

    long linkedToMay =
        countAsTenant(
            TENANT_A,
            "SELECT count(*) FROM expense_claim WHERE id = ? AND reimbursement_run_id = ?",
            setup.claimId(),
            mayRunId);
    long linkedToJune =
        countAsTenant(
            TENANT_A,
            "SELECT count(*) FROM expense_claim WHERE id = ? AND reimbursement_run_id = ?",
            setup.claimId(),
            juneRunId);
    // Exactly one of the two — never both (a double-link would double-settle later), never
    // neither (the claim must not vanish from both runs' linking attempts).
    assertThat(linkedToMay + linkedToJune).isEqualTo(1L);
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'APPROVED'",
                setup.claimId()))
        .isEqualTo(1L);
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  private RunPayrollCommand runCommand(String period, UUID employeeId) {
    return new RunPayrollCommand(period, List.of(employeeId), List.of());
  }

  /**
   * One employee (comp package + assignment, ready for payroll) with ONE APPROVED, un-linked,
   * {@code PAYROLL}-method expense claim (the default reimbursement method).
   */
  private ClaimSetup setUpEmployeeWithApprovedClaim(String nik, String bankAccount)
      throws Exception {
    UUID outlet = UUID.randomUUID();
    TenantContext.runAs(
        TENANT_A,
        ACTOR_A,
        () ->
            orgProjectionService.apply(
                new OrgUnitProjectedEvent(
                    UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true)));

    UUID employeeId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              seeder.seed(IDR);
              UUID id =
                  employeeService
                      .create(new CreateEmployeeCommand("Budi", "TK0", nik, bankAccount))
                      .getId();
              employeeService.linkUser(id, CLAIMANT_ACTOR, null);
              var contract =
                  employeeService.addContract(
                      new AddContractCommand(
                          id,
                          "PERMANENT",
                          LEGAL_EMPLOYER,
                          LocalDate.of(2026, 1, 1),
                          LocalDate.of(9999, 12, 31)));
              compensationWriter.createPackage(
                  id,
                  contract.getId(),
                  Money.ofMinor(BASE_MINOR, IDR),
                  LocalDate.of(2026, 1, 1),
                  LocalDate.of(9999, 12, 31));
              assignmentService.add(
                  new AddAssignmentCommand(
                      id,
                      outlet,
                      null,
                      "cashier",
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(9999, 12, 31)));
              return id;
            });

    UUID claimId =
        TenantContext.callAs(
            TENANT_A,
            CLAIMANT_ACTOR,
            () -> {
              UUID categoryId = categoryWriter.create("Supplies-" + nik, "supplies", false).getId();
              UUID id =
                  claimService
                      .create(
                          new CreateClaimCommand(
                              categoryId,
                              250_000L,
                              IDR,
                              LocalDate.of(2026, 7, 15),
                              "Warung Makan",
                              "lunch",
                              null))
                      .getId();
              claimService.submit(id, "submit-" + id);
              return id;
            });
    TenantContext.callAs(
        TENANT_A, MANAGER_ACTOR, () -> claimService.approve(claimId, "ok", "approve-" + claimId));

    return new ClaimSetup(employeeId, claimId);
  }

  /**
   * Simulates Track P Phase P7's future {@code EXPENSE_REIMBURSEMENT} payslip line — P7 does not
   * exist yet, so no production writer produces this row today. Goes through {@link
   * TestPayslipLineWriter} (a real JPA write via a proper {@code REQUIRES_NEW} proxy, not a raw
   * INSERT) so {@code amount_enc} is GENUINELY encrypted via {@code MoneyPiiConverter} (rule 6) and
   * RLS's {@code WITH CHECK} is satisfied — a raw-SQL placeholder string in that ciphertext column
   * fails entity hydration the moment any code re-loads the row (as {@code PayrollRunWriter#post}'s
   * liability computation does for every line on the run).
   */
  private void insertExpenseReimbursementPayslipLine(UUID runId, UUID employeeId) throws Exception {
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          PayslipLine line =
              new PayslipLine(
                  runId,
                  employeeId,
                  UUID.randomUUID(),
                  "EXPENSE_REIMBURSEMENT",
                  PayComponentKind.EARNING,
                  PayComponentBearer.EMPLOYEE,
                  "5300-EXPENSE-REIMBURSEMENT",
                  Money.ofMinor(250_000L, IDR),
                  null,
                  null,
                  false);
          line.setCompanyId(TENANT_A);
          return testPayslipLineWriter.save(line);
        });
  }

  /**
   * Replays, at the raw-SQL level, the SHAPE of the optimistic-locked UPDATE {@code
   * ExpenseClaimWriter#payDirect}'s {@code claimRepository.save(claim)} issues on flush (mirrors
   * {@code Auditable}'s JPA {@code @Version} behaviour: {@code WHERE id = ? AND version = ?})
   * against a caller-supplied, possibly-STALE {@code version} — a deterministic simulation of "a
   * payDirect flush built from a pre-link snapshot" (ADR 0030 §6 BINDING), not a literal capture of
   * Hibernate's generated SQL (column list/ordering may differ). Runs as the unprivileged {@code
   * app_user} (RLS-scoped, {@link PostgresRlsTestBase#countAsTenant}'s connection idiom) so a
   * genuinely version-mismatched attempt behaves the same way the real optimistic-locked JPA UPDATE
   * would: {@code ExpenseClaimWriterTest} (expense package) separately proves, at the mock level,
   * that {@code payDirect()} never catches/swallows the resulting {@code
   * ObjectOptimisticLockingFailureException} — the two tests together cover both the SQL-shape and
   * the exception-propagation halves of the same invariant.
   *
   * @return the number of rows the UPDATE affected — {@code 0} means the version no longer matches
   *     (the race was lost), {@code 1} means it would have succeeded
   */
  private int attemptStaleVersionedPayDirectUpdate(UUID claimId, long staleVersion)
      throws Exception {
    try (Connection con =
        DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
      try (PreparedStatement set =
          con.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
        set.setString(1, TENANT_A);
        set.execute();
      }
      try (PreparedStatement ps =
          con.prepareStatement(
              "UPDATE expense_claim SET status = 'REIMBURSED', settled_at = NOW(),"
                  + " updated_at = NOW(), version = version + 1 WHERE id = ? AND version = ?")) {
        ps.setObject(1, claimId);
        ps.setLong(2, staleVersion);
        return ps.executeUpdate();
      }
    }
  }
}
