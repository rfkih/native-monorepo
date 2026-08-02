package id.co.nativeapp.employee.expense.controller;

import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.domain.ReimbursementMethod;
import id.co.nativeapp.employee.expense.dto.CreateClaimCommand;
import id.co.nativeapp.employee.expense.dto.CreateClaimRequest;
import id.co.nativeapp.employee.expense.dto.ExpenseClaimResponse;
import id.co.nativeapp.employee.expense.dto.MyExpenseClaimResponse;
import id.co.nativeapp.employee.expense.service.ExpenseClaimReader;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/me/expense-claims} — the employee self-service surface (ADR 0030): draft, edit,
 * submit, cancel, and read back one's own claims. The caller is resolved EXCLUSIVELY from the bound
 * tenant/actor (the {@code /me} idiom, rule 5) — there is no employee-id parameter anywhere, so a
 * caller can only ever touch their own rows.
 *
 * <p>The create/update bodies are shared with the create shape ({@link CreateClaimRequest}); only
 * {@code submit}/{@code cancel} are guarded state transitions and require an {@code
 * Idempotency-Key} header (missing → 400).
 */
@Tag(
    name = "My Expense Claims",
    description = "Self-service expense claims: draft, edit, submit, cancel, and read own claims")
@RestController
@RequestMapping("/api/v1/me/expense-claims")
public class MyExpenseClaimController {

  private final ExpenseClaimService claimService;
  private final ExpenseClaimReader claimReader;

  public MyExpenseClaimController(
      ExpenseClaimService claimService, ExpenseClaimReader claimReader) {
    this.claimService = claimService;
    this.claimReader = claimReader;
  }

  @Operation(summary = "Create a draft expense claim for the caller")
  @PostMapping
  public ResponseEntity<ExpenseClaimResponse> create(
      @Valid @RequestBody CreateClaimRequest request) {
    ExpenseClaim claim = claimService.create(toCommand(request));
    ExpenseClaimResponse body = ExpenseClaimResponse.from(claim);
    return ResponseEntity.created(URI.create("/api/v1/me/expense-claims/" + body.id())).body(body);
  }

  @Operation(summary = "Replace a DRAFT expense claim's editable fields (full replace)")
  @PutMapping("/{id}")
  public ExpenseClaimResponse update(
      @PathVariable UUID id, @Valid @RequestBody CreateClaimRequest request) {
    ExpenseClaim claim = claimService.updateDraft(id, toCommand(request));
    return ExpenseClaimResponse.from(claim);
  }

  @Operation(summary = "Submit a DRAFT expense claim for approval")
  @PostMapping("/{id}/submit")
  public ExpenseClaimResponse submit(
      @PathVariable UUID id, @RequestHeader("Idempotency-Key") String idempotencyKey) {
    ExpenseClaim claim = claimService.submit(id, idempotencyKey);
    return ExpenseClaimResponse.from(claim);
  }

  @Operation(summary = "Cancel a DRAFT or SUBMITTED expense claim")
  @PostMapping("/{id}/cancel")
  public ExpenseClaimResponse cancel(
      @PathVariable UUID id, @RequestHeader("Idempotency-Key") String idempotencyKey) {
    ExpenseClaim claim = claimService.cancel(id, idempotencyKey);
    return ExpenseClaimResponse.from(claim);
  }

  @Operation(summary = "List the caller's own expense claims")
  @GetMapping
  public List<MyExpenseClaimResponse> list() {
    return claimReader.myClaims();
  }

  @Operation(summary = "Get one of the caller's own expense claims")
  @GetMapping("/{id}")
  public ExpenseClaimResponse get(@PathVariable UUID id) {
    return claimReader.myClaim(id);
  }

  private static CreateClaimCommand toCommand(CreateClaimRequest request) {
    ReimbursementMethod method =
        request.reimbursementMethod() == null
            ? null
            : ReimbursementMethod.valueOf(request.reimbursementMethod());
    return new CreateClaimCommand(
        request.categoryId(),
        request.amountMinor(),
        request.currency(),
        request.expenseDate(),
        request.merchant(),
        request.note(),
        method);
  }
}
