package id.co.nativeapp.finance.companyexpense.service;

import id.co.nativeapp.finance.companyexpense.domain.CompanyExpense;
import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseIdempotencyConflictException;
import id.co.nativeapp.finance.companyexpense.projection.CompanyExpenseLineView;
import id.co.nativeapp.finance.companyexpense.repository.CompanyExpenseLineRepository;
import id.co.nativeapp.finance.companyexpense.repository.CompanyExpenseRepository;
import java.util.List;
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
public class CompanyExpenseReplayReader {

  private final CompanyExpenseRepository expenseRepository;
  private final CompanyExpenseLineRepository lineRepository;

  public CompanyExpenseReplayReader(
      CompanyExpenseRepository expenseRepository, CompanyExpenseLineRepository lineRepository) {
    this.expenseRepository = expenseRepository;
    this.lineRepository = lineRepository;
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

  /** Mirrors {@code CompanyExpenseLine}'s normalisation so the comparison sees the stored shape. */
  private static String normalizedDescription(String description, String ingredientName) {
    if (description == null) {
      return null;
    }
    String trimmed = description.strip();
    return trimmed.isEmpty() || trimmed.equals(ingredientName) ? null : trimmed;
  }

  private UUID requireSamePayload(
      CompanyExpense existing, CompanyExpenseWriter.RecordCommand command) {
    boolean same =
        existing.getKind() == command.kind()
            && existing.getBusinessId().equals(command.businessId())
            && existing.getGlHint().equals(command.glHint())
            && existing.getDescription().equals(command.description())
            && existing.amount().equals(command.amount())
            // The INVENTORY line set too (review W1): the amount is the server-computed line SUM,
            // so edited lines with an unchanged total would otherwise replay silently and the
            // stock receive would be for the OLD lines.
            && sameLines(existing, command);
    if (!same) {
      throw new CompanyExpenseIdempotencyConflictException(command.idempotencyKey());
    }
    return existing.getId();
  }

  private boolean sameLines(CompanyExpense existing, CompanyExpenseWriter.RecordCommand command) {
    List<CompanyExpenseLineView> stored = lineRepository.findViewsByExpenseId(existing.getId());
    if (stored.size() != command.lines().size()) {
      return false;
    }
    for (int i = 0; i < stored.size(); i++) {
      CompanyExpenseLineView s = stored.get(i);
      CompanyExpenseWriter.RecordCommand.LineCommand c = command.lines().get(i);
      if (!s.getIngredientId().equals(c.ingredientId())
          || s.getQtyBase() != c.qtyBase()
          || s.getValueMinor() != c.value().amountMinor()
          // The receipt wording is part of the payload: an edited nota name under the same key is
          // a DIFFERENT submit, not a replay (compared against the same normalisation the entity
          // applies, so blank and "equals the ingredient name" both read as null).
          || !java.util.Objects.equals(
              s.getDescription(), normalizedDescription(c.description(), c.ingredientName()))) {
        return false;
      }
    }
    return true;
  }
}
