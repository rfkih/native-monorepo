package id.co.nativeapp.finance.bank.controller;

import id.co.nativeapp.finance.bank.dto.BankAccountResponse;
import id.co.nativeapp.finance.bank.dto.CreateBankAccountRequest;
import id.co.nativeapp.finance.bank.dto.ImportStatementLinesRequest;
import id.co.nativeapp.finance.bank.dto.StatementLineResponse;
import id.co.nativeapp.finance.bank.dto.UpdateBankAccountRequest;
import id.co.nativeapp.finance.bank.service.BankAccountReader;
import id.co.nativeapp.finance.bank.service.BankAccountWriter;
import id.co.nativeapp.finance.bank.service.StatementLineInput;
import id.co.nativeapp.finance.bank.service.StatementLineReader;
import id.co.nativeapp.finance.bank.service.StatementLineWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/bank-accounts} — bank accounts, and their imported statement lines, for the bound
 * tenant. All reads/writes are RLS-scoped; the tenant comes from {@link
 * id.co.nativeapp.tenant.TenantContext} (gateway-injected), never the body. Exposes DTOs only
 * (never the JPA entity). The statement-line endpoints live under the OWNING bank account (not a
 * separate top-level resource), so import/list are naturally scoped without a redundant {@code
 * bankAccountId} body field.
 */
@Tag(
    name = "Bank Accounts",
    description = "Bank accounts + their statement lines for the bound tenant.")
@RestController
@RequestMapping("/api/v1/bank-accounts")
@Validated
public class BankAccountController {

  private final BankAccountWriter bankAccountWriter;
  private final BankAccountReader bankAccountReader;
  private final StatementLineWriter statementLineWriter;
  private final StatementLineReader statementLineReader;

  public BankAccountController(
      BankAccountWriter bankAccountWriter,
      BankAccountReader bankAccountReader,
      StatementLineWriter statementLineWriter,
      StatementLineReader statementLineReader) {
    this.bankAccountWriter = bankAccountWriter;
    this.bankAccountReader = bankAccountReader;
    this.statementLineWriter = statementLineWriter;
    this.statementLineReader = statementLineReader;
  }

  @Operation(summary = "Create a bank account")
  @PostMapping
  public ResponseEntity<BankAccountResponse> create(
      @Valid @RequestBody CreateBankAccountRequest request) {
    BankAccountResponse created =
        bankAccountWriter.create(request.name(), request.accountNumber(), request.currency());
    return ResponseEntity.created(URI.create("/api/v1/bank-accounts/" + created.id()))
        .body(created);
  }

  @Operation(summary = "List all bank accounts in the tenant, ordered by name")
  @GetMapping
  public List<BankAccountResponse> list() {
    return bankAccountReader.list();
  }

  @Operation(summary = "Get a bank account by id")
  @GetMapping("/{id}")
  public BankAccountResponse get(@PathVariable UUID id) {
    return bankAccountReader.get(id);
  }

  @Operation(summary = "Update a bank account's details and/or active flag")
  @PatchMapping("/{id}")
  public BankAccountResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateBankAccountRequest request) {
    return bankAccountWriter.update(id, request.name(), request.accountNumber(), request.active());
  }

  @Operation(summary = "Import statement lines for a bank account (saved UNRECONCILED)")
  @PostMapping("/{id}/statement-lines")
  public ResponseEntity<List<StatementLineResponse>> importLines(
      @PathVariable UUID id, @Valid @RequestBody ImportStatementLinesRequest request) {
    List<StatementLineInput> inputs =
        request.lines().stream()
            .map(
                l ->
                    new StatementLineInput(
                        l.lineDate(), l.amountMinor(), l.description(), l.reference()))
            .toList();
    List<StatementLineResponse> imported = statementLineWriter.importLines(id, inputs);
    return ResponseEntity.status(HttpStatus.CREATED).body(imported);
  }

  @Operation(summary = "List statement lines for a bank account, optionally filtered by status")
  @GetMapping("/{id}/statement-lines")
  public List<StatementLineResponse> listLines(
      @PathVariable UUID id,
      @RequestParam(required = false)
          @Pattern(
              regexp = "UNRECONCILED|RECONCILED",
              message = "status must be UNRECONCILED or RECONCILED")
          String status) {
    return statementLineReader.list(id, status);
  }
}
