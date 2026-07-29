package id.co.nativeapp.finance.budget.dto;

import java.util.List;
import java.util.UUID;

/** A budget with its planned lines (Phase 5) — the detail view. Amounts are minor units. */
public record BudgetResponse(
    UUID id, String name, String period, String currency, List<BudgetLineDto> lines) {

  /** One planned line: the account (code + name + type) and its planned amount. */
  public record BudgetLineDto(
      String accountCode, String accountName, String accountType, long amountMinor) {}
}
