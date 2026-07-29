package id.co.nativeapp.finance.budget.controller;

import id.co.nativeapp.finance.budget.dto.AccountResponse;
import id.co.nativeapp.finance.budget.dto.BudgetActualsResponse;
import id.co.nativeapp.finance.budget.dto.BudgetResponse;
import id.co.nativeapp.finance.budget.dto.BudgetSummaryResponse;
import id.co.nativeapp.finance.budget.dto.CreateBudgetRequest;
import id.co.nativeapp.finance.budget.service.AccountCatalogReader;
import id.co.nativeapp.finance.budget.service.BudgetActualReader;
import id.co.nativeapp.finance.budget.service.BudgetLineInput;
import id.co.nativeapp.finance.budget.service.BudgetReader;
import id.co.nativeapp.finance.budget.service.BudgetWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/budgets} — per-month budgets for the bound tenant (Phase 5, ADR 0019): create, list,
 * detail, the budget-vs-actual variance report, and delete, plus the chart-of-account picker. Budgets
 * are planning data — nothing here posts to the GL. Every response is a DTO; typed faults map to
 * RFC-7807 via {@link BudgetAdvice} (404 not-found, 400 bad-input/unknown-account, 409 duplicate, 422
 * multi-currency).
 */
@Tag(name = "Budgets", description = "Per-month budgets + budget-vs-actual variance for the tenant.")
@RestController
@RequestMapping("/api/v1/budgets")
@Validated
public class BudgetController {

  private final BudgetWriter budgetWriter;
  private final BudgetReader budgetReader;
  private final BudgetActualReader budgetActualReader;
  private final AccountCatalogReader accountCatalogReader;

  public BudgetController(
      BudgetWriter budgetWriter,
      BudgetReader budgetReader,
      BudgetActualReader budgetActualReader,
      AccountCatalogReader accountCatalogReader) {
    this.budgetWriter = budgetWriter;
    this.budgetReader = budgetReader;
    this.budgetActualReader = budgetActualReader;
    this.accountCatalogReader = accountCatalogReader;
  }

  @Operation(summary = "Create a per-month budget with its planned lines")
  @PostMapping
  public ResponseEntity<BudgetResponse> create(@Valid @RequestBody CreateBudgetRequest request) {
    List<BudgetLineInput> lines =
        request.lines().stream()
            .map(l -> new BudgetLineInput(l.accountCode(), l.amountMinor()))
            .toList();
    UUID id = budgetWriter.create(request.name(), request.period(), request.currency(), lines);
    BudgetResponse detail = budgetReader.detail(id);
    return ResponseEntity.created(URI.create("/api/v1/budgets/" + detail.id())).body(detail);
  }

  @Operation(summary = "List budgets for the bound tenant (most-recent period first)")
  @GetMapping
  public List<BudgetSummaryResponse> list() {
    return budgetReader.list();
  }

  @Operation(summary = "The chart of accounts (for the budget create-form account picker)")
  @GetMapping("/accounts")
  public List<AccountResponse> accounts() {
    return accountCatalogReader.list();
  }

  @Operation(summary = "Get a budget's detail (header + planned lines)")
  @GetMapping("/{id}")
  public BudgetResponse get(@PathVariable UUID id) {
    return budgetReader.detail(id);
  }

  @Operation(summary = "Budget-vs-actual variance for a budget (planned vs the period's GL actuals)")
  @GetMapping("/{id}/actuals")
  public BudgetActualsResponse actuals(@PathVariable UUID id) {
    return budgetActualReader.actuals(id);
  }

  @Operation(summary = "Delete a budget and its lines")
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    budgetWriter.delete(id);
    return ResponseEntity.noContent().build();
  }
}
