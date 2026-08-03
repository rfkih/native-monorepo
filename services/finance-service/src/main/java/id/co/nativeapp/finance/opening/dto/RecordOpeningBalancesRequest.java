package id.co.nativeapp.finance.opening.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Request to record a company's opening balance sheet (ADR 0037). {@code asOfDate} is the go-live /
 * day-before date (drives the {@code YYYY-MM} posting period); {@code currency} is the single
 * ISO-4217 code every line transacts in. {@code lines} are the balance-sheet carrying values
 * (assets as debits, liabilities/equity as credits); the writer auto-plugs any residual to Opening
 * Balance Equity so the entry always balances. Brought-forward fixed assets are registered
 * separately (they post their own cash-free entry).
 */
public record RecordOpeningBalancesRequest(
    @NotNull @PastOrPresent LocalDate asOfDate,
    @NotBlank @Size(min = 3, max = 3) String currency,
    @NotEmpty @Valid List<OpeningBalanceLine> lines) {}
