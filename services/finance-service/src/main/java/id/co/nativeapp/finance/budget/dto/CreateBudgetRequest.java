package id.co.nativeapp.finance.budget.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body to create a per-month budget with its planned lines (Phase 5). Amounts are integer
 * minor units in {@code currency} (never a float); each line targets a chart-of-account account.
 */
public record CreateBudgetRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "period must be a valid YYYY-MM month")
        String period,
    @NotBlank @Size(min = 3, max = 3) String currency,
    @NotEmpty @Size(max = 500) @Valid List<LineInput> lines) {

  /** One planned line: an account and its non-negative planned amount (minor units). */
  public record LineInput(
      @NotBlank @Size(max = 32) String accountCode, @Min(0) long amountMinor) {}
}
