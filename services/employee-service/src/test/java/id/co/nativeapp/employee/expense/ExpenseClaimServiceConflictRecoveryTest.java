package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.employee.expense.service.ExpenseClaimWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Deterministic proof of {@link ExpenseClaimService}'s conflict-recovery branch — the one a
 * concurrent idempotency-key insert race triggers — without depending on thread timing. Mirrors
 * {@code SaleServiceConflictRecoveryTest}.
 */
class ExpenseClaimServiceConflictRecoveryTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "manager-sub";
  private static final UUID CLAIM = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");

  private final ExpenseClaimWriter writer = mock(ExpenseClaimWriter.class);
  private final ExpenseClaimService service = new ExpenseClaimService(writer);

  private static ExpenseClaim existingClaim() {
    return new ExpenseClaim(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Money.ofMinor(250_000L, "IDR"),
        LocalDate.of(2026, 7, 15),
        null,
        null,
        null);
  }

  @Test
  void aUniqueConstraintConflictRecoversTheExistingClaimOnApprove() throws Exception {
    ExpenseClaim existing = existingClaim();
    when(writer.approve(CLAIM, "ok", "k-1"))
        .thenThrow(new DataIntegrityViolationException("dup key"));
    when(writer.findByIdForReplay(CLAIM)).thenReturn(Optional.of(existing));

    ExpenseClaim result =
        TenantContext.callAs(TENANT, ACTOR, () -> service.approve(CLAIM, "ok", "k-1"));

    assertThat(result).isSameAs(existing);
  }

  @Test
  void aConflictWithNoRecoverableRowRethrowsOnApprove() throws Exception {
    when(writer.approve(CLAIM, "ok", "k-1"))
        .thenThrow(new DataIntegrityViolationException("dup key"));
    when(writer.findByIdForReplay(CLAIM)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> service.approve(CLAIM, "ok", "k-1")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aUniqueConstraintConflictRecoversTheExistingClaimOnSubmit() throws Exception {
    ExpenseClaim existing = existingClaim();
    when(writer.submit(any(), any())).thenThrow(new DataIntegrityViolationException("dup key"));
    when(writer.findByIdForReplay(CLAIM)).thenReturn(Optional.of(existing));

    ExpenseClaim result = TenantContext.callAs(TENANT, ACTOR, () -> service.submit(CLAIM, "k-2"));

    assertThat(result).isSameAs(existing);
  }
}
