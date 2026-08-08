package id.co.nativeapp.employee.expense;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.employee.config.EmployeeApiAdvice;
import id.co.nativeapp.employee.expense.controller.ExpenseClaimController;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.domain.ClaimStateException;
import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.domain.ReceiptNotFoundException;
import id.co.nativeapp.employee.expense.domain.SelfApprovalException;
import id.co.nativeapp.employee.expense.dto.ExpenseClaimResponse;
import id.co.nativeapp.employee.expense.dto.OrgUnitExpenseSummaryResponse;
import id.co.nativeapp.employee.expense.dto.PageResponse;
import id.co.nativeapp.employee.expense.service.ExpenseClaimReader;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.employee.expense.service.ReceiptReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link ExpenseClaimController}: validation 400s, missing {@code
 * Idempotency-Key} 400, 404 anti-enumeration, self-approval 403, and happy paths. Services mocked.
 */
@WebMvcTest(ExpenseClaimController.class)
@Import({EmployeeApiAdvice.class, ApiExceptionHandler.class})
class ExpenseClaimControllerTest {

  private static final UUID CLAIM = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
  private static final UUID CATEGORY = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
  private static final UUID EMPLOYEE = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");
  private static final UUID ORG_UNIT = UUID.fromString("dddddddd-0000-0000-0000-00000000000d");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ExpenseClaimService claimService;
  @MockitoBean private ExpenseClaimReader claimReader;
  @MockitoBean private ReceiptReader receiptReader;

  private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02};

  private static ExpenseClaim submittedClaim() {
    ExpenseClaim claim =
        new ExpenseClaim(
            EMPLOYEE,
            CATEGORY,
            ORG_UNIT,
            Money.ofMinor(250_000L, "IDR"),
            LocalDate.of(2026, 7, 15),
            "Warung Makan",
            "lunch",
            null);
    claim.submit();
    return claim;
  }

  @Test
  void listReturns200WithThePaginationEnvelope() throws Exception {
    when(claimReader.forManager(any(), any(), any(), any()))
        .thenReturn(PageResponse.of(List.of(), 0, 25, 0));

    mockMvc
        .perform(get("/api/v1/expense-claims"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.totalPages").value(0));
  }

  @Test
  void listWithAnUnknownStatusIs400() throws Exception {
    when(claimReader.forManager(eq("bogus"), any(), any(), any()))
        .thenThrow(new IllegalArgumentException("Unknown expense claim status: bogus"));

    mockMvc.perform(get("/api/v1/expense-claims?status=bogus")).andExpect(status().isBadRequest());
  }

  @Test
  void getOfAnUnknownClaimIs404() throws Exception {
    when(claimReader.one(CLAIM)).thenThrow(new ClaimNotFoundException(CLAIM));

    mockMvc
        .perform(get("/api/v1/expense-claims/" + CLAIM))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/expense-claim-not-found"));
  }

  @Test
  void getReturns200() throws Exception {
    when(claimReader.one(CLAIM)).thenReturn(ExpenseClaimResponse.from(submittedClaim()));

    mockMvc
        .perform(get("/api/v1/expense-claims/" + CLAIM))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(ClaimStatus.SUBMITTED.name()));
  }

  // ---------------------------------------------------------------------
  // summary (org-unit hub Expenses tab rollup — Phase E8)
  // ---------------------------------------------------------------------

  @Test
  void summaryReturns200WithTheRollupShape() throws Exception {
    when(claimReader.summary(any(), any()))
        .thenReturn(
            new OrgUnitExpenseSummaryResponse(
                List.of(new OrgUnitExpenseSummaryResponse.CategoryTotal("Travel", 300_000L, "IDR")),
                List.of(new OrgUnitExpenseSummaryResponse.StatusCount("SUBMITTED", 1L)),
                300_000L,
                "IDR"));

    mockMvc
        .perform(get("/api/v1/expense-claims/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.byCategory[0].categoryName").value("Travel"))
        .andExpect(jsonPath("$.byCategory[0].totalMinor").value(300_000))
        .andExpect(jsonPath("$.byStatus[0].status").value("SUBMITTED"))
        .andExpect(jsonPath("$.approvedReimbursedTotalMinor").value(300_000))
        .andExpect(jsonPath("$.currency").value("IDR"));
  }

  @Test
  void summaryWithAMalformedPeriodIs400() throws Exception {
    when(claimReader.summary(any(), eq("bogus")))
        .thenThrow(new IllegalArgumentException("period must be YYYY-MM: bogus"));

    mockMvc
        .perform(get("/api/v1/expense-claims/summary?period=bogus"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void approveWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void approveReturns200() throws Exception {
    ExpenseClaim approved = submittedClaim();
    approved.approve("manager-sub", "ok", Instant.parse("2026-07-20T09:00:00Z"));
    when(claimService.approve(eq(CLAIM), isNull(), eq("k-1"))).thenReturn(approved);

    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/approve")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(ClaimStatus.APPROVED.name()));
  }

  @Test
  void approveOwnClaimIs403() throws Exception {
    when(claimService.approve(any(), any(), any())).thenThrow(new SelfApprovalException(CLAIM));

    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/approve")
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/self-approval-forbidden"));
  }

  @Test
  void refuseWithoutACommentIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/refuse")
                .header("Idempotency-Key", "k-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void refuseWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/refuse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"no receipt\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void refuseReturns200() throws Exception {
    ExpenseClaim refused = submittedClaim();
    refused.refuse("manager-sub", "no receipt", Instant.parse("2026-07-20T09:00:00Z"));
    when(claimService.refuse(eq(CLAIM), eq("no receipt"), eq("k-4"))).thenReturn(refused);

    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/refuse")
                .header("Idempotency-Key", "k-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"no receipt\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(ClaimStatus.REFUSED.name()));
  }

  @Test
  void aConflictThatIsNotAGenuineReplaySurfacesAs409NotA500() throws Exception {
    // S1/S2: ExpenseClaimService rethrows the raw DataIntegrityViolationException when the raced
    // idempotency key does NOT belong to a genuine replay of THIS action (e.g. the same key reused
    // for a different action on the same claim) — the controller must map it to 409, never let it
    // fall to the shared catch-all 500.
    when(claimService.approve(any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException("dup key, different action"));

    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/approve")
                .header("Idempotency-Key", "k-reused")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/expense-concurrent-conflict"));
  }

  // ---------------------------------------------------------------------
  // pay (DIRECT reimbursement — ADR 0030 §6, Phase E4)
  // ---------------------------------------------------------------------

  @Test
  void payWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(post("/api/v1/expense-claims/" + CLAIM + "/pay"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void payReturns200() throws Exception {
    ExpenseClaim paid = submittedClaim();
    paid.approve("manager-sub", "ok", Instant.parse("2026-07-20T09:00:00Z"));
    paid.payDirect(Instant.parse("2026-07-21T10:00:00Z"));
    when(claimService.payDirect(eq(CLAIM), eq("k-5"))).thenReturn(paid);

    mockMvc
        .perform(post("/api/v1/expense-claims/" + CLAIM + "/pay").header("Idempotency-Key", "k-5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(ClaimStatus.REIMBURSED.name()));
  }

  @Test
  void payAcceptsAnOptionalEmptyBody() throws Exception {
    ExpenseClaim paid = submittedClaim();
    paid.approve("manager-sub", "ok", Instant.parse("2026-07-20T09:00:00Z"));
    paid.payDirect(Instant.parse("2026-07-21T10:00:00Z"));
    when(claimService.payDirect(eq(CLAIM), eq("k-6"))).thenReturn(paid);

    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/pay")
                .header("Idempotency-Key", "k-6")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(ClaimStatus.REIMBURSED.name()));
  }

  @Test
  void payOfAnUnknownClaimIs404() throws Exception {
    when(claimService.payDirect(eq(CLAIM), eq("k-7"))).thenThrow(new ClaimNotFoundException(CLAIM));

    mockMvc
        .perform(post("/api/v1/expense-claims/" + CLAIM + "/pay").header("Idempotency-Key", "k-7"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/expense-claim-not-found"));
  }

  // ---------------------------------------------------------------------
  // void (the correction path — ADR 0030 §5, Phase E7)
  // ---------------------------------------------------------------------

  @Test
  void voidWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/void")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"dup\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void voidWithoutACommentIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/void")
                .header("Idempotency-Key", "k-void-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void voidReturns200() throws Exception {
    ExpenseClaim voided = submittedClaim();
    voided.approve("manager-sub", "ok", Instant.parse("2026-07-20T09:00:00Z"));
    voided.voidClaim("manager-2", "dup", Instant.parse("2026-07-22T11:00:00Z"));
    when(claimService.voidClaim(eq(CLAIM), eq("dup"), eq("k-void-2"))).thenReturn(voided);

    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/void")
                .header("Idempotency-Key", "k-void-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"dup\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(ClaimStatus.VOIDED.name()));
  }

  @Test
  void voidOfAnUnknownClaimIs404() throws Exception {
    when(claimService.voidClaim(eq(CLAIM), eq("dup"), eq("k-void-3")))
        .thenThrow(new ClaimNotFoundException(CLAIM));

    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/void")
                .header("Idempotency-Key", "k-void-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"dup\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/expense-claim-not-found"));
  }

  @Test
  void voidOfAnIneligibleClaimIs409() throws Exception {
    when(claimService.voidClaim(eq(CLAIM), eq("dup"), eq("k-void-4")))
        .thenThrow(new ClaimStateException(ClaimStatus.SUBMITTED, "void"));

    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/void")
                .header("Idempotency-Key", "k-void-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"dup\"}"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/expense-claim-state-conflict"));
  }

  // ---------------------------------------------------------------------
  // Receipt serve (manager surface — ADR 0030 §8, Phase E3)
  // ---------------------------------------------------------------------

  @Test
  void getReceiptReturns200WithTheBytesAndCorrectHeaders() throws Exception {
    when(receiptReader.receiptForManager(CLAIM))
        .thenReturn(new ReceiptContentResponse("image/jpeg", JPEG_BYTES.clone(), "a".repeat(64)));

    mockMvc
        .perform(get("/api/v1/expense-claims/" + CLAIM + "/receipt"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/jpeg"))
        .andExpect(header().longValue("Content-Length", JPEG_BYTES.length))
        .andExpect(content().bytes(JPEG_BYTES));
  }

  @Test
  void getReceiptOfAnUnknownClaimIs404() throws Exception {
    when(receiptReader.receiptForManager(CLAIM)).thenThrow(new ClaimNotFoundException(CLAIM));

    mockMvc
        .perform(get("/api/v1/expense-claims/" + CLAIM + "/receipt"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/expense-claim-not-found"));
  }

  @Test
  void getReceiptWhenNoneOnFileIs404() throws Exception {
    when(receiptReader.receiptForManager(CLAIM)).thenThrow(new ReceiptNotFoundException(CLAIM));

    mockMvc
        .perform(get("/api/v1/expense-claims/" + CLAIM + "/receipt"))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/expense-receipt-not-found"));
  }
}
