package id.co.nativeapp.finance.statements.controller;

import id.co.nativeapp.finance.statements.dto.BalanceSheetResponse;
import id.co.nativeapp.finance.statements.dto.IncomeStatementResponse;
import id.co.nativeapp.finance.statements.service.BalanceSheetReader;
import id.co.nativeapp.finance.statements.service.IncomeStatementReader;
import jakarta.validation.constraints.Pattern;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Financial statements read API — Income Statement and Balance Sheet derived from the double-entry
 * GL. Both endpoints are read-only, tenant-scoped via RLS (rule 5), and return DTOs at the boundary
 * (no {@code @Entity} on the wire).
 *
 * <p>The tenant ({@code company_id}) comes from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext} (set at the request edge by the gateway / dev
 * filter), never from the query parameters. RLS on {@code journal_entry} and {@code journal_line}
 * scopes every read to the bound company (rule 5).
 *
 * <p><strong>Deferred / follow-ups (SME-gated):</strong>
 *
 * <ul>
 *   <li>Cash Flow statement (indirect method) — a later sub-phase.
 *   <li>SAK-EMKM exact presentation grouping and labels — SME review required before finalising the
 *       Indonesian MSME presentation. The current response returns structured data (account-type
 *       groups, line items, subtotals, totals) with {@code usesIllustrativeRules} flagged; the
 *       precise Indonesian grouping comes later.
 *   <li>Comparative columns (prior-period) — deferred.
 *   <li>Historical backfill — not in scope (on-read derivation only).
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/statements")
@Validated
public class StatementsController {

  private final IncomeStatementReader incomeStatementReader;
  private final BalanceSheetReader balanceSheetReader;

  public StatementsController(
      IncomeStatementReader incomeStatementReader, BalanceSheetReader balanceSheetReader) {
    this.incomeStatementReader = incomeStatementReader;
    this.balanceSheetReader = balanceSheetReader;
  }

  /**
   * Income Statement (Laba Rugi) for the requested accounting period.
   *
   * <p>PERIOD-scoped: covers only journal entries whose {@code period} equals {@code period}.
   * REVENUE − EXPENSE = net, grouped by account. Returns {@code 204 No Content} when the period has
   * no GL entries (mirrors the PnlController / RevenueController no-data pattern). {@code 204}
   * means "no journal entries at all in the period" — a period with only balance-sheet activity
   * (e.g. a capital injection: DR Cash / CR Equity) still returns {@code 200} with a zero-activity
   * P&amp;L ({@code netMinor == 0}), since the GL is non-empty.
   *
   * @param period the accounting period {@code YYYY-MM} (validated; an impossible month is a 400)
   */
  @GetMapping("/income")
  public ResponseEntity<IncomeStatementResponse> incomeStatement(
      @RequestParam
          @Pattern(
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "period must be a valid YYYY-MM month")
          String period) {

    Optional<IncomeStatementResponse> result = incomeStatementReader.read(period);
    return result.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
  }

  /**
   * Balance Sheet (Laporan Posisi Keuangan) as of the end of the requested period.
   *
   * <p>CUMULATIVE: sums all GL journal activity from inception up to and including {@code asOf}.
   * ASSETS on one side; LIABILITIES + EQUITY (including computed retained earnings) on the other.
   * The service guarantees total assets == total liabilities + equity (incl. retained earnings); an
   * imbalance throws 500. Returns {@code 204 No Content} when there are no GL entries at all.
   *
   * @param asOf the as-of period {@code YYYY-MM}; the balance sheet reflects the state at the end
   *     of this period
   */
  @GetMapping("/balance-sheet")
  public ResponseEntity<BalanceSheetResponse> balanceSheet(
      @RequestParam
          @Pattern(
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "asOf must be a valid YYYY-MM month")
          String asOf) {

    Optional<BalanceSheetResponse> result = balanceSheetReader.read(asOf);
    return result.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
  }
}
