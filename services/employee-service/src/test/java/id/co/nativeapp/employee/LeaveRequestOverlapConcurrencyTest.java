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
}
