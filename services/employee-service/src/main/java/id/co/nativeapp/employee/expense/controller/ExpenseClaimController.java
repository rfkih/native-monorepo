package id.co.nativeapp.employee.expense.controller;

import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.dto.ApproveClaimRequest;
import id.co.nativeapp.employee.expense.dto.ExpenseClaimResponse;
import id.co.nativeapp.employee.expense.dto.ExpenseClaimSummaryResponse;
import id.co.nativeapp.employee.expense.dto.PageResponse;
import id.co.nativeapp.employee.expense.dto.ReceiptContentResponse;
import id.co.nativeapp.employee.expense.dto.RefuseClaimRequest;
import id.co.nativeapp.employee.expense.service.ExpenseClaimReader;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.employee.expense.service.ReceiptReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/expense-claims} — the manager/owner surface (ADR 0030): the tenant-wide claim
 * list/detail plus the approve/refuse decisions, plus (Phase E3) streaming any tenant claim's
 * receipt photo. {@code approve}/{@code refuse} are guarded state transitions and require an {@code
 * Idempotency-Key} header (missing → 400). Self-approval is rejected with 403, owners included.
 * This surface rides the EXISTING {@code /api/v1/expense-claims/**} gateway route — no new route
 * was needed for the receipt GET (ADR 0030 §8, Phase E3 spike).
 */
@Tag(
    name = "Expense Claims",
    description = "Manager surface: the tenant-wide claim list, approve, and refuse")
@RestController
@RequestMapping("/api/v1/expense-claims")
public class ExpenseClaimController {

  private final ExpenseClaimService claimService;
  private final ExpenseClaimReader claimReader;
  private final ReceiptReader receiptReader;

  public ExpenseClaimController(
      ExpenseClaimService claimService,
      ExpenseClaimReader claimReader,
      ReceiptReader receiptReader) {
    this.claimService = claimService;
    this.claimReader = claimReader;
    this.receiptReader = receiptReader;
  }

  @Operation(summary = "List expense claims for the tenant, optionally filtered (paginated)")
  @GetMapping
  public PageResponse<ExpenseClaimSummaryResponse> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) List<UUID> orgUnitId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return claimReader.forManager(status, orgUnitId, page, size);
  }

  @Operation(summary = "Get one expense claim")
  @GetMapping("/{id}")
  public ExpenseClaimResponse get(@PathVariable UUID id) {
    return claimReader.one(id);
  }

  @Operation(summary = "Approve a SUBMITTED expense claim — recognises the expense at approval")
  @PostMapping("/{id}/approve")
  public ExpenseClaimResponse approve(
      @PathVariable UUID id,
      @Valid @RequestBody ApproveClaimRequest request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    ExpenseClaim claim = claimService.approve(id, request.comment(), idempotencyKey);
    return ExpenseClaimResponse.from(claim);
  }

  @Operation(summary = "Refuse a SUBMITTED expense claim — a comment is required")
  @PostMapping("/{id}/refuse")
  public ExpenseClaimResponse refuse(
      @PathVariable UUID id,
      @Valid @RequestBody RefuseClaimRequest request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    ExpenseClaim claim = claimService.refuse(id, request.comment(), idempotencyKey);
    return ExpenseClaimResponse.from(claim);
  }

  @Operation(summary = "Stream the receipt photo for any expense claim in the tenant")
  @GetMapping("/{id}/receipt")
  public ResponseEntity<byte[]> getReceipt(@PathVariable UUID id) {
    ReceiptContentResponse content =
        ReceiptContentResponse.from(receiptReader.receiptForManager(id));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(content.contentType()))
        .contentLength(content.data().length)
        .body(content.data());
  }
}
