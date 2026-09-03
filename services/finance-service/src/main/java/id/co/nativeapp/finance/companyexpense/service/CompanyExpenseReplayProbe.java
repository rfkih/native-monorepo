package id.co.nativeapp.finance.companyexpense.service;

import id.co.nativeapp.finance.companyexpense.domain.CompanyExpense;
import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseIdempotencyConflictException;
import id.co.nativeapp.finance.companyexpense.repository.CompanyExpenseRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Idempotency-Key replay probe, in its own {@code REQUIRES_NEW} transaction so the RLS aspect
 * binds the tenant GUC for the read (a raw un-bound read fails closed to zero rows and would make
 * every retry look fresh) and so the post-conflict recovery re-read sees the racing winner's
 * COMMITTED row. A distinct bean from {@link CompanyExpenseService} — self-invocation would bypass
 * the proxy (rule 5).
 */
@Component
public class CompanyExpenseReplayProbe {

  private final CompanyExpenseRepository expenseRepository;

  public CompanyExpenseReplayProbe(CompanyExpenseRepository expenseRepository) {
    this.expenseRepository = expenseRepository;
  }

  /**
   * Looks up an existing expense recorded under the command's Idempotency-Key. Same payload → that
   * expense's id (a clean replay); different payload → 409. Fields that the server defaults
   * (occurred_at) are deliberately NOT compared — a retry that re-defaults them must still replay.
   *
   * @return the existing id, or empty when the key is unused
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<UUID> findReplay(CompanyExpenseWriter.RecordCommand command) {
    return expenseRepository
        .findByIdempotencyKey(command.idempotencyKey())
        .map(existing -> requireSamePayload(existing, command));
  }

  private static UUID requireSamePayload(
      CompanyExpense existing, CompanyExpenseWriter.RecordCommand command) {
    boolean same =
        existing.getKind() == command.kind()
            && existing.getBusinessId().equals(command.businessId())
            && existing.getGlHint().equals(command.glHint())
            && existing.getDescription().equals(command.description())
            && existing.amount().equals(command.amount());
    if (!same) {
      throw new CompanyExpenseIdempotencyConflictException(command.idempotencyKey());
    }
    return existing.getId();
  }
}
