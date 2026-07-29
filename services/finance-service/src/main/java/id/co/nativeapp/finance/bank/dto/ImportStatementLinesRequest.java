package id.co.nativeapp.finance.bank.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Request body to import statement lines for one bank account. Each line carries a date, a SIGNED
 * amount in the bank account's minor units (positive = deposit, negative = withdrawal — never a
 * float), and an optional description/reference. The currency is NOT client-supplied: every
 * imported line inherits the bank account's own (immutable) currency.
 */
public record ImportStatementLinesRequest(
    @NotEmpty @Size(max = 500) @Valid List<LineRequest> lines) {

  /** One requested statement line. */
  public record LineRequest(
      @NotNull LocalDate lineDate,
      long amountMinor,
      @Size(max = 500) String description,
      @Size(max = 128) String reference) {}
}
