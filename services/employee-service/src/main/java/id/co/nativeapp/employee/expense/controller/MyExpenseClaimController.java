package id.co.nativeapp.employee.expense.controller;

import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.domain.ExpenseReceipt;
import id.co.nativeapp.employee.expense.domain.ReimbursementMethod;
import id.co.nativeapp.employee.expense.dto.CreateClaimCommand;
import id.co.nativeapp.employee.expense.dto.CreateClaimRequest;
import id.co.nativeapp.employee.expense.dto.ExpenseCategoryResponse;
import id.co.nativeapp.employee.expense.dto.ExpenseClaimResponse;
import id.co.nativeapp.employee.expense.dto.MyExpenseClaimResponse;
import id.co.nativeapp.employee.expense.dto.PageResponse;
import id.co.nativeapp.employee.expense.dto.ReceiptContentResponse;
import id.co.nativeapp.employee.expense.dto.ReceiptMetaResponse;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryReader;
import id.co.nativeapp.employee.expense.service.ExpenseClaimReader;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.employee.expense.service.ReceiptReader;
import id.co.nativeapp.employee.expense.service.ReceiptWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /api/v1/me/expense-claims} — the employee self-service surface (ADR 0030): draft, edit,
 * submit, cancel, and read back one's own claims, plus (Phase E3) uploading/serving the claim's
 * receipt photo and (Phase E6) the active category picklist a new claim is raised against. The
 * caller is resolved EXCLUSIVELY from the bound tenant/actor (the {@code /me} idiom, rule 5) —
 * there is no employee-id parameter anywhere, so a caller can only ever touch their own rows.
 *
 * <p>The create/update bodies are shared with the create shape ({@link CreateClaimRequest}); only
 * {@code submit}/{@code cancel} are guarded state transitions and require an {@code
 * Idempotency-Key} header (missing → 400). The receipt upload is deliberately NOT idempotency-key
 * guarded — it is replace-idempotent by nature ({@code ReceiptWriter}'s Javadoc).
 */
@Tag(
    name = "My Expense Claims",
    description = "Self-service expense claims: draft, edit, submit, cancel, and read own claims")
@RestController
@RequestMapping("/api/v1/me/expense-claims")
public class MyExpenseClaimController {

  private final ExpenseClaimService claimService;
  private final ExpenseClaimReader claimReader;
  private final ReceiptWriter receiptWriter;
  private final ReceiptReader receiptReader;
  private final ExpenseCategoryReader categoryReader;

  public MyExpenseClaimController(
      ExpenseClaimService claimService,
      ExpenseClaimReader claimReader,
      ReceiptWriter receiptWriter,
      ReceiptReader receiptReader,
      ExpenseCategoryReader categoryReader) {
    this.claimService = claimService;
    this.claimReader = claimReader;
    this.receiptWriter = receiptWriter;
    this.receiptReader = receiptReader;
    this.categoryReader = categoryReader;
  }

  @Operation(summary = "List active expense categories a claim may be raised against")
  @GetMapping("/categories")
  public List<ExpenseCategoryResponse> categories() {
    return categoryReader.list();
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

  @Operation(summary = "List the caller's own expense claims (paginated)")
  @GetMapping
  public PageResponse<MyExpenseClaimResponse> list(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    return claimReader.myClaims(page, size);
  }

  @Operation(summary = "Get one of the caller's own expense claims")
  @GetMapping("/{id}")
  public ExpenseClaimResponse get(@PathVariable UUID id) {
    return claimReader.myClaim(id);
  }

  @Operation(summary = "Upload (or replace) the receipt photo for the caller's own DRAFT claim")
  @PostMapping(path = "/{id}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ReceiptMetaResponse> uploadReceipt(
      @PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
    ExpenseReceipt receipt = receiptWriter.upload(id, file.getContentType(), file.getBytes());
    return ResponseEntity.status(HttpStatus.CREATED).body(ReceiptMetaResponse.from(receipt));
  }

  @Operation(summary = "Stream the receipt photo for one of the caller's own claims")
  @GetMapping("/{id}/receipt")
  public ResponseEntity<byte[]> getReceipt(@PathVariable UUID id) {
    return receiptResponse(ReceiptContentResponse.from(receiptReader.myReceipt(id)));
  }

  private static ResponseEntity<byte[]> receiptResponse(ReceiptContentResponse content) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(content.contentType()))
        .contentLength(content.data().length)
        .body(content.data());
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
