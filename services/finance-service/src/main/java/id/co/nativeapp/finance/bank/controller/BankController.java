package id.co.nativeapp.finance.bank.controller;

import id.co.nativeapp.finance.bank.domain.ReconciliationCategory;
import id.co.nativeapp.finance.bank.dto.ReconcileRequest;
import id.co.nativeapp.finance.bank.dto.ReconciliationReportResponse;
import id.co.nativeapp.finance.bank.dto.StatementLineResponse;
import id.co.nativeapp.finance.bank.service.ReconciliationReader;
import id.co.nativeapp.finance.bank.service.ReconciliationWriter;
import id.co.nativeapp.finance.bank.service.StatementLineReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/bank} — reconciling a bank statement line against the GL, and the reconciliation
 * report, for the bound tenant. All reads/writes are RLS-scoped; exposes DTOs only (never the JPA
 * entity). The statement-line CRUD/import endpoints live under {@code /api/v1/bank-accounts/**}
 * ({@link BankAccountController}), not here.
 */
@Tag(name = "Bank Reconciliation", description = "Reconcile bank statement lines against the GL.")
@RestController
@RequestMapping("/api/v1/bank")
public class BankController {

  private final ReconciliationWriter reconciliationWriter;
  private final ReconciliationReader reconciliationReader;
  private final StatementLineReader statementLineReader;

  public BankController(
      ReconciliationWriter reconciliationWriter,
      ReconciliationReader reconciliationReader,
      StatementLineReader statementLineReader) {
    this.reconciliationWriter = reconciliationWriter;
    this.reconciliationReader = reconciliationReader;
    this.statementLineReader = statementLineReader;
  }

  @Operation(
      summary =
          "Reconcile a statement line against a category (posts a balanced transfer to the GL)")
  @PostMapping("/reconcile/{lineId}")
  public StatementLineResponse reconcile(
      @PathVariable UUID lineId, @Valid @RequestBody ReconcileRequest request) {
    ReconciliationCategory category = ReconciliationCategory.valueOf(request.category());
    UUID reconciledId = reconciliationWriter.reconcile(lineId, category);
    return statementLineReader.get(reconciledId);
  }

  @Operation(
      summary =
          "Reconciliation report for a bank account: bank balance, CASH_CLEARING GL balance, and"
              + " unreconciled lines")
  @GetMapping("/reconciliation")
  public ReconciliationReportResponse reconciliation(@RequestParam UUID bankAccountId) {
    return reconciliationReader.report(bankAccountId);
  }
}
