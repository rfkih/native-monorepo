package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.timeoff.domain.LeaveOverlapException;
import id.co.nativeapp.employee.timeoff.domain.LeaveType;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The headline CONCURRENCY proof for the overlap guard (ADR 0033 §4): two threads submit
 * OVERLAPPING leave requests for the SAME employee, at the same instant, under DIFFERENT
 * idempotency keys (two genuinely distinct client submissions, not a retry of the same one). The
 * per-employee advisory lock ({@code LeaveRequestWriter}) serializes the overlap check + insert, so
 * EXACTLY ONE succeeds and the other observes the just-committed row and is rejected with {@link
 * LeaveOverlapException} — never both succeeding (which would silently double-book the same days),
 * and never a raw/unhandled exception on the loser.
 */
@SpringBootTest
class LeaveRequestOverlapConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String CLAIMANT_ACTOR = "aaaaaaaa-1111-1111-1111-111111111111";

  @Autowired private EmployeeService employeeService;
  @Autowired private LeaveRequestService leaveRequestService;

  @Test
  void concurrentOverlappingSubmitsYieldExactlyOneWinner() throws Exception {
    TenantContext.callAs(
        TENANT_A,
        CLAIMANT_ACTOR,
        () -> {
          var employeeId =
              employeeService
                  .create(
                      new CreateEmployeeCommand(
                          "Budi", "TK0", "3201234567890123", "1234567890123456"))
                  .getId();
          employeeService.linkUser(employeeId, CLAIMANT_ACTOR, null);
          return employeeId;
        });

    CyclicBarrier barrier = new CyclicBarrier(2);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger overlapConflicts = new AtomicInteger();

    Callable<Void> attempt1 =
        () ->
            TenantContext.callAs(
                TENANT_A,
                CLAIMANT_ACTOR,
                () -> {
                  barrier.await();
                  try {
                    leaveRequestService.create(
                        LeaveType.ANNUAL,
                        LocalDate.of(2026, 8, 10),
                        LocalDate.of(2026, 8, 12),
                        2,
                        "overlap-race-1");
                    successes.incrementAndGet();
                  } catch (LeaveOverlapException expected) {
                    overlapConflicts.incrementAndGet();
                  }
                  return null;
                });
    Callable<Void> attempt2 =
        () ->
            TenantContext.callAs(
                TENANT_A,
                CLAIMANT_ACTOR,
                () -> {
                  barrier.await();
                  try {
                    leaveRequestService.create(
                        LeaveType.ANNUAL,
                        LocalDate.of(2026, 8, 11),
                        LocalDate.of(2026, 8, 13),
                        2,
                        "overlap-race-2");
                    successes.incrementAndGet();
                  } catch (LeaveOverlapException expected) {
                    overlapConflicts.incrementAndGet();
                  }
                  return null;
                });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Void> f1 = pool.submit(attempt1);
      Future<Void> f2 = pool.submit(attempt2);
      f1.get();
      f2.get();
    } finally {
      pool.shutdownNow();
    }

    assertThat(successes.get()).isEqualTo(1);
    assertThat(overlapConflicts.get()).isEqualTo(1);
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM leave_request WHERE idempotency_key LIKE 'overlap-race-%'"))
        .isEqualTo(1L);
  }

  // ---------------------------------------------------------------------
  // Sequential (non-race) overlap-guard proofs against real Postgres (Track P Phase P7 review W1).
  // ---------------------------------------------------------------------

  /**
   * A CANCELLED request's dates must NOT block a later overlapping submission — {@code
   * existsOverlapping} filters {@code status IN ('SUBMITTED', 'APPROVED')} only.
   */
  @Test
  void aCancelledRequestDoesNotBlockAnOverlappingResubmission() throws Exception {
    var employeeId = createEmployeeWithLogin("3211111111111111", "1111000011110000");

    var first =
        TenantContext.callAs(
            TENANT_A,
            CLAIMANT_ACTOR,
            () ->
                leaveRequestService.create(
                    LeaveType.ANNUAL,
                    LocalDate.of(2026, 8, 3),
                    LocalDate.of(2026, 8, 5),
                    3,
                    "w1-cancel-first"));
    TenantContext.callAs(
        TENANT_A, CLAIMANT_ACTOR, () -> leaveRequestService.cancel(first.getId(), "w1-cancel-key"));

    // Same exact range, a genuinely different request — must succeed, not throw
    // LeaveOverlapException.
    var second =
        TenantContext.callAs(
            TENANT_A,
            CLAIMANT_ACTOR,
            () ->
                leaveRequestService.create(
                    LeaveType.ANNUAL,
                    LocalDate.of(2026, 8, 3),
                    LocalDate.of(2026, 8, 5),
                    3,
                    "w1-cancel-second"));

    assertThat(second.getId()).isNotEqualTo(first.getId());
    assertThat(employeeId).isNotNull();
  }

  /**
   * A REJECTED request's dates must NOT block a later overlapping submission — same {@code status
   * IN (...)} filter reasoning as the CANCELLED case above.
   */
  @Test
  void aRejectedRequestDoesNotBlockAnOverlappingResubmission() throws Exception {
    createEmployeeWithLogin("3212222222222222", "2222000022220000");

    var first =
        TenantContext.callAs(
            TENANT_A,
            CLAIMANT_ACTOR,
            () ->
                leaveRequestService.create(
                    LeaveType.ANNUAL,
                    LocalDate.of(2026, 9, 3),
                    LocalDate.of(2026, 9, 5),
                    3,
                    "w1-reject-first"));
    TenantContext.callAs(
        TENANT_A,
        CLAIMANT_ACTOR,
        () -> leaveRequestService.reject(first.getId(), "not needed", "w1-reject-decision"));

    var second =
        TenantContext.callAs(
            TENANT_A,
            CLAIMANT_ACTOR,
            () ->
                leaveRequestService.create(
                    LeaveType.ANNUAL,
                    LocalDate.of(2026, 9, 3),
                    LocalDate.of(2026, 9, 5),
                    3,
                    "w1-reject-second"));

    assertThat(second.getId()).isNotEqualTo(first.getId());
  }

  /**
   * Back-to-back ranges SHARING an endpoint day (Mon-Wed vs Wed-Fri, both include Wednesday) ARE an
   * overlap — the inclusive {@code start_date <= :endDate AND end_date >= :startDate} predicate
   * correctly treats a shared boundary day as a genuine conflict (an employee cannot be on leave
   * AND back at work the same calendar day under two different requests).
   */
  @Test
  void backToBackRangesSharingAnEndpointDayConflict() throws Exception {
    createEmployeeWithLogin("3213333333333333", "3333000033330000");

    TenantContext.callAs(
        TENANT_A,
        CLAIMANT_ACTOR,
        () ->
            leaveRequestService.create(
                LeaveType.ANNUAL,
                LocalDate.of(2026, 10, 5), // Monday
                LocalDate.of(2026, 10, 7), // Wednesday
                3,
                "w1-backtoback-first"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    CLAIMANT_ACTOR,
                    () ->
                        leaveRequestService.create(
                            LeaveType.ANNUAL,
                            LocalDate.of(2026, 10, 7), // Wednesday — shared with the first request
                            LocalDate.of(2026, 10, 9), // Friday
                            3,
                            "w1-backtoback-second")))
        .isInstanceOf(LeaveOverlapException.class);
  }

  private String createEmployeeWithLogin(String nik, String bankAccount) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        CLAIMANT_ACTOR,
        () -> {
          var employeeId =
              employeeService
                  .create(new CreateEmployeeCommand("Budi", "TK0", nik, bankAccount))
                  .getId();
          employeeService.linkUser(employeeId, CLAIMANT_ACTOR, null);
          return employeeId.toString();
        });
  }
}
