package id.co.nativeapp.finance.companyexpense.service;

import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseKind;
import id.co.nativeapp.finance.companyexpense.domain.InvalidCompanyExpenseException;
import id.co.nativeapp.finance.companyexpense.dto.RecordCompanyExpenseRequest;
import id.co.nativeapp.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a company-expense submit (ADR 0072): parses/normalizes the raw DTO into the writer's
 * validated command, and implements the Idempotency-Key replay contract around the writer's
 * transaction — probe first (a clean retry returns the existing id), then record, and on the
 * partial-unique race ({@code uq_company_expense_idempotency}) re-read in a FRESH transaction (the
 * {@code IngredientService#addStock} race-recovery pattern). Same key + different payload is a 409,
 * never a second expense.
 *
 * <p>Deliberately NOT {@code @Transactional}: each step below must run in its own transaction (the
 * probe and the recovery re-read need their own RLS-bound tx; the record is the writer's).
 */
@Service
public class CompanyExpenseService {

  private final CompanyExpenseWriter writer;
  private final CompanyExpenseReplayReader replayReader;
  private final Clock clock;

  public CompanyExpenseService(
      CompanyExpenseWriter writer, CompanyExpenseReplayReader replayReader, Clock clock) {
    this.writer = writer;
    this.replayReader = replayReader;
    this.clock = clock;
  }

  /**
   * Records the expense (or replays an identical keyed retry).
   *
   * @return the expense id — new, or the existing one on a replay
   */
  public UUID record(RecordCompanyExpenseRequest request, String idempotencyKey) {
    CompanyExpenseWriter.RecordCommand command = toCommand(request, idempotencyKey);
    if (idempotencyKey != null) {
      Optional<UUID> replayed = replayReader.findReplay(command);
      if (replayed.isPresent()) {
        return replayed.get();
      }
    }
    try {
      return writer.record(command);
    } catch (DataIntegrityViolationException e) {
      // Two same-key submits raced past the probe; the partial-unique index serialized them.
      // Recover by re-reading the winner in a fresh tx — same payload replays, different conflicts.
      if (idempotencyKey != null) {
        Optional<UUID> winner = replayReader.findReplay(command);
        if (winner.isPresent()) {
          return winner.get();
        }
      }
      throw e;
    }
  }

  /** Voids a POSTED expense (money-side contra only; stock is fixed forward). */
  public UUID voidExpense(UUID expenseId) {
    return writer.voidExpense(expenseId);
  }

  private CompanyExpenseWriter.RecordCommand toCommand(
      RecordCompanyExpenseRequest request, String idempotencyKey) {
    if (request == null) {
      throw new InvalidCompanyExpenseException("a request body is required");
    }
    CompanyExpenseKind kind = parseKind(request.kind());
    if (request.businessId() == null) {
      throw new InvalidCompanyExpenseException("businessId is required");
    }
    if (request.currency() == null || request.currency().isBlank()) {
      throw new InvalidCompanyExpenseException("currency is required");
    }
    Currency currency;
    try {
      currency = Currency.getInstance(request.currency());
    } catch (IllegalArgumentException e) {
      throw new InvalidCompanyExpenseException("unknown currency: " + request.currency());
    }
    Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : clock.instant();
    String glHint = request.glHint() == null ? "" : request.glHint().strip();
    String description = request.description() == null ? "" : request.description().strip();

    List<CompanyExpenseWriter.RecordCommand.LineCommand> lines = new ArrayList<>();
    long lineSum = 0;
    if (request.lines() != null) {
      for (RecordCompanyExpenseRequest.LineRequest line : request.lines()) {
        if (line.ingredientId() == null || line.qtyBase() == null || line.valueMinor() == null) {
          throw new InvalidCompanyExpenseException(
              "every line needs ingredientId, qtyBase and valueMinor");
        }
        lines.add(
            new CompanyExpenseWriter.RecordCommand.LineCommand(
                line.ingredientId(),
                line.ingredientName() == null ? "" : line.ingredientName().strip(),
                line.qtyBase(),
                Money.ofMinor(line.valueMinor(), currency)));
        lineSum += line.valueMinor();
      }
    }
    // INVENTORY's authoritative amount is the line sum (the client total is display-only);
    // GENERAL's is the submitted amount.
    long amountMinor =
        kind == CompanyExpenseKind.INVENTORY
            ? lineSum
            : request.amountMinor() == null ? 0L : request.amountMinor();
    return new CompanyExpenseWriter.RecordCommand(
        kind,
        request.businessId(),
        kind == CompanyExpenseKind.INVENTORY ? "" : glHint,
        description,
        Money.ofMinor(amountMinor, currency),
        occurredAt,
        lines,
        idempotencyKey);
  }

  private static CompanyExpenseKind parseKind(String raw) {
    if (raw == null) {
      throw new InvalidCompanyExpenseException("kind is required (GENERAL or INVENTORY)");
    }
    try {
      return CompanyExpenseKind.valueOf(raw);
    } catch (IllegalArgumentException e) {
      throw new InvalidCompanyExpenseException("unknown kind: " + raw);
    }
  }
}
