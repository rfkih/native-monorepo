package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.timeoff.domain.InsufficientLeaveBalanceException;
import id.co.nativeapp.employee.timeoff.domain.LeaveType;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
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
 * The headline CONCURRENCY proof for the ANNUAL-leave balance guard (ADR 0033 §4): two DIFFERENT
 * SUBMITTED requests for the SAME employee, each 7 days (default grant is 12 — together they exceed
 * it), approved by a manager at the same instant on two threads. The per-employee advisory lock
 * ({@code LeaveRequestWriter#approve}) serializes the balance check + state flip, so EXACTLY ONE
 * approval succeeds and the other observes the just-approved 7 days already counted and is rejected
 * with {@link InsufficientLeaveBalanceException} (409) — never both succeeding (which would
 * silently over-approve the employee's balance).
 */
@SpringBootTest
class LeaveRequestBalanceConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String CLAIMANT_ACTOR = "aaaaaaaa-1111-1111-1111-111111111111";
  private static final String MANAGER_ACTOR = "manager-not-linked";

  @Autowired private EmployeeService employeeService;
  @Autowired private LeaveRequestService leaveRequestService;

  @Test
  void concurrentApprovalsThatTogetherExceedTheBalanceYieldExactlyOneWinner() throws Exception {
    record Ids(UUID request1, UUID request2) {}

    Ids ids =
        TenantContext.callAs(
            TENANT_A,
            CLAIMANT_ACTOR,
            () -> {
              UUID employeeId =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Dewi", "TK0", "3201234567890124", "1234567890123457"))
                      .getId();
              employeeService.linkUser(employeeId, CLAIMANT_ACTOR, null);
              UUID request1 =
                  leaveRequestService
                      .create(
                          LeaveType.ANNUAL,
                          LocalDate.of(2026, 3, 2),
                          LocalDate.of(2026, 3, 8),
                          7,
                          "bal-req-1")
                      .getId();
              UUID request2 =
                  leaveRequestService
                      .create(
                          LeaveType.ANNUAL,
                          LocalDate.of(2026, 6, 1),
                          LocalDate.of(2026, 6, 7),
                          7,
                          "bal-req-2")
                      .getId();
              return new Ids(request1, request2);
            });

    CyclicBarrier barrier = new CyclicBarrier(2);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger insufficientBalanceConflicts = new AtomicInteger();

    Callable<Void> approve1 =
        () ->
            TenantContext.callAs(
                TENANT_A,
                MANAGER_ACTOR,
                () -> {
                  barrier.await();
                  try {
                    leaveRequestService.approve(ids.request1(), "ok", "bal-approve-1");
                    successes.incrementAndGet();
                  } catch (InsufficientLeaveBalanceException expected) {
                    insufficientBalanceConflicts.incrementAndGet();
                  }
                  return null;
                });
    Callable<Void> approve2 =
        () ->
            TenantContext.callAs(
                TENANT_A,
                MANAGER_ACTOR,
                () -> {
                  barrier.await();
                  try {
                    leaveRequestService.approve(ids.request2(), "ok", "bal-approve-2");
                    successes.incrementAndGet();
                  } catch (InsufficientLeaveBalanceException expected) {
                    insufficientBalanceConflicts.incrementAndGet();
                  }
                  return null;
                });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Void> f1 = pool.submit(approve1);
      Future<Void> f2 = pool.submit(approve2);
      f1.get();
      f2.get();
    } finally {
      pool.shutdownNow();
    }

    assertThat(successes.get()).isEqualTo(1);
    assertThat(insufficientBalanceConflicts.get()).isEqualTo(1);
    assertThat(
            countAsTenant(TENANT_A, "SELECT count(*) FROM leave_request WHERE status = 'APPROVED'"))
        .isEqualTo(1L);
  }
}
