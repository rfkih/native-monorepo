package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.domain.ExpenseCategory;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.dto.CreateClaimCommand;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryWriter;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The headline CONCURRENCY + ATOMICITY proof: two threads approve the SAME SUBMITTED claim with the
 * SAME {@code Idempotency-Key} at the same time.
 *
 * <p>Proves both invariants ADR 0030 §5/§7 depend on together:
 *
 * <ul>
 *   <li><strong>One winner.</strong> Exactly one caller flips the claim (and only one claim row
 *       ever exists — this is an UPDATE, not an insert race); both callers return successfully (no
 *       unhandled 500) via {@link ExpenseClaimService}'s conflict-recovery re-read.
 *   <li><strong>Approve = state flip + outbox row in ONE transaction.</strong> The loser's
 *       transaction aborts on the {@code expense_claim_event} unique-constraint collision, which
 *       rolls back EVERYTHING it did in that same transaction — its claim-status UPDATE and its
 *       {@code ExpenseClaimApproved} outbox INSERT included. So exactly ONE {@code
 *       expense_claim_event} APPROVE row and exactly ONE outbox row exist — never two, proving the
 *       state flip and the event emission commit (or roll back) as a single unit, not two
 *       independent writes that could diverge under a race.
 * </ul>
 */
@SpringBootTest
class ExpenseClaimApproveConcurrencyTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String CLAIMANT_ACTOR = "aaaaaaaa-1111-1111-1111-111111111111";
  private static final String MANAGER_ACTOR = "manager-not-linked-to-any-employee";

  @Autowired private EmployeeService employeeService;
  @Autowired private ExpenseCategoryWriter categoryWriter;
  @Autowired private ExpenseClaimService claimService;

  @Test
  void concurrentApproveWithSameKeyYieldsOneWinnerAndAtomicStateFlipPlusOutbox() throws Exception {
    UUID claimId =
        TenantContext.callAs(
            TENANT_A,
            CLAIMANT_ACTOR,
            () -> {
              UUID employeeId =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Budi", "TK0", "3201234567890123", "1234567890123456"))
                      .getId();
              employeeService.linkUser(employeeId, CLAIMANT_ACTOR, null);

              ExpenseCategory category = categoryWriter.create("Supplies", "supplies", false);

              ExpenseClaim claim =
                  claimService.create(
                      new CreateClaimCommand(
                          category.getId(),
                          250_000L,
                          "IDR",
                          LocalDate.of(2026, 7, 15),
                          "Warung Makan",
                          "lunch",
                          null));
              return claimService.submit(claim.getId(), "submit-key").getId();
            });

    String idempotencyKey = "race-approve-key";
    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<ExpenseClaim> attempt =
        () ->
            TenantContext.callAs(
                TENANT_A,
                MANAGER_ACTOR,
                () -> {
                  barrier.await();
                  return claimService.approve(claimId, "ok", idempotencyKey);
                });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ExpenseClaim> f1 = pool.submit(attempt);
      Future<ExpenseClaim> f2 = pool.submit(attempt);

      // Neither call throws (no 500) — both resolve to the SAME approved claim.
      ExpenseClaim r1 = f1.get();
      ExpenseClaim r2 = f2.get();
      assertThat(r1.getId()).isEqualTo(claimId);
      assertThat(r2.getId()).isEqualTo(claimId);
      assertThat(r1.getStatus()).isEqualTo(ClaimStatus.APPROVED);
      assertThat(r2.getStatus()).isEqualTo(ClaimStatus.APPROVED);
    } finally {
      pool.shutdownNow();
    }

    // Exactly ONE APPROVE audit row for this claim (counted under tenant A's RLS scope — a bare
    // JdbcTemplate carries no GUC and FORCE RLS would hide every row).
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim_event WHERE claim_id = ? AND action ="
                    + " 'APPROVE'",
                claimId))
        .isEqualTo(1L);

    // Exactly ONE ExpenseClaimApproved outbox row — the loser's transaction rolled back whole.
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM outbox WHERE event_type = 'ExpenseClaimApproved'"
                    + " AND aggregate_id = ?",
                claimId.toString()))
        .isEqualTo(1L);
  }
}
