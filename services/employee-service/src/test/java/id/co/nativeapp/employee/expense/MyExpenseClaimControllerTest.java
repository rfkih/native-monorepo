package id.co.nativeapp.employee.expense;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.employee.config.EmployeeApiAdvice;
import id.co.nativeapp.employee.expense.controller.MyExpenseClaimController;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.domain.ClaimStateException;
import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.domain.ExpenseCategory;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.domain.ExpenseReceipt;
import id.co.nativeapp.employee.expense.domain.InvalidReceiptContentTypeException;
import id.co.nativeapp.employee.expense.domain.ReceiptNotFoundException;
import id.co.nativeapp.employee.expense.dto.ExpenseCategoryResponse;
import id.co.nativeapp.employee.expense.dto.ExpenseClaimResponse;
import id.co.nativeapp.employee.expense.dto.PageResponse;
import id.co.nativeapp.employee.expense.dto.ReceiptContentResponse;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryReader;
import id.co.nativeapp.employee.expense.service.ExpenseClaimReader;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.employee.expense.service.ReceiptReader;
import id.co.nativeapp.employee.expense.service.ReceiptWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Web-slice test for {@link MyExpenseClaimController}: validation 400s, missing {@code
 * Idempotency-Key} 400, 404 anti-enumeration, and happy paths. Services mocked.
 */
@WebMvcTest(MyExpenseClaimController.class)
@Import({EmployeeApiAdvice.class, ApiExceptionHandler.class})
class MyExpenseClaimControllerTest {

  private static final UUID CLAIM = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
  private static final UUID CATEGORY = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
  private static final UUID EMPLOYEE = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");
  private static final UUID ORG_UNIT = UUID.fromString("dddddddd-0000-0000-0000-00000000000d");

  private static final String VALID_BODY =
      "{\"categoryId\":\""
          + CATEGORY
          + "\",\"amountMinor\":250000,\"currency\":\"IDR\","
          + "\"expenseDate\":\"2026-07-15\",\"merchant\":\"Warung Makan\",\"note\":\"lunch\"}";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ExpenseClaimService claimService;
  @MockitoBean private ExpenseClaimReader claimReader;
  @MockitoBean private ReceiptWriter receiptWriter;
  @MockitoBean private ReceiptReader receiptReader;
  @MockitoBean private ExpenseCategoryReader categoryReader;

  private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02};

  private static ExpenseReceipt sampleReceipt() {
    return new ExpenseReceipt(CLAIM, "image/jpeg", JPEG_BYTES.clone(), "a".repeat(64));
  }

  private static ExpenseClaim sampleDraft() {
    return new ExpenseClaim(
        EMPLOYEE,
        CATEGORY,
        ORG_UNIT,
        Money.ofMinor(250_000L, "IDR"),
        LocalDate.of(2026, 7, 15),
        "Warung Makan",
        "lunch",
        null);
  }

  // ---------------------------------------------------------------------
  // Category picklist (ADR 0030, Phase E6)
  // ---------------------------------------------------------------------

  @Test
  void categoriesReturns200WithTheActiveList() throws Exception {
    ExpenseCategory category = new ExpenseCategory("Transport", "", false);
    when(categoryReader.list()).thenReturn(List.of(ExpenseCategoryResponse.from(category)));

    mockMvc
        .perform(get("/api/v1/me/expense-claims/categories"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Transport"))
        .andExpect(jsonPath("$[0].active").value(true));
  }

  @Test
  void createReturns201WithLocation() throws Exception {
    when(claimService.create(any())).thenReturn(sampleDraft());

    mockMvc
        .perform(
            post("/api/v1/me/expense-claims")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.status").value(ClaimStatus.DRAFT.name()))
        .andExpect(jsonPath("$.amountMinor").value(250_000));
  }

  @Test
  void createWithAMissingCategoryIdIs400() throws Exception {
    String body = "{\"amountMinor\":250000,\"currency\":\"IDR\",\"expenseDate\":\"2026-07-15\"}";
    mockMvc
        .perform(
            post("/api/v1/me/expense-claims").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createWithANonPositiveAmountIs400() throws Exception {
    String body =
        "{\"categoryId\":\""
            + CATEGORY
            + "\",\"amountMinor\":0,\"currency\":\"IDR\",\"expenseDate\":\"2026-07-15\"}";
    mockMvc
        .perform(
            post("/api/v1/me/expense-claims").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createWithALowercaseCurrencyIs400() throws Exception {
    String body =
        "{\"categoryId\":\""
            + CATEGORY
            + "\",\"amountMinor\":250000,\"currency\":\"idr\",\"expenseDate\":\"2026-07-15\"}";
    mockMvc
        .perform(
            post("/api/v1/me/expense-claims").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateReturns200() throws Exception {
    when(claimService.updateDraft(eq(CLAIM), any())).thenReturn(sampleDraft());

    mockMvc
        .perform(
            put("/api/v1/me/expense-claims/" + CLAIM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(ClaimStatus.DRAFT.name()));
  }

  @Test
  void updateOfAnUnknownClaimIs404() throws Exception {
    when(claimService.updateDraft(eq(CLAIM), any())).thenThrow(new ClaimNotFoundException(CLAIM));

    mockMvc
        .perform(
            put("/api/v1/me/expense-claims/" + CLAIM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/expense-claim-not-found"));
  }

  @Test
  void updateOfANonDraftClaimIs409() throws Exception {
    when(claimService.updateDraft(eq(CLAIM), any()))
        .thenThrow(new ClaimStateException(ClaimStatus.SUBMITTED, "edit"));

    mockMvc
        .perform(
            put("/api/v1/me/expense-claims/" + CLAIM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/expense-claim-state-conflict"));
  }

  @Test
  void submitWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(post("/api/v1/me/expense-claims/" + CLAIM + "/submit"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void submitReturns200() throws Exception {
    ExpenseClaim submitted = sampleDraft();
    submitted.submit();
    when(claimService.submit(eq(CLAIM), eq("k-1"))).thenReturn(submitted);

    mockMvc
        .perform(
            post("/api/v1/me/expense-claims/" + CLAIM + "/submit").header("Idempotency-Key", "k-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(ClaimStatus.SUBMITTED.name()));
  }

  @Test
  void cancelWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(post("/api/v1/me/expense-claims/" + CLAIM + "/cancel"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void cancelReturns200() throws Exception {
    ExpenseClaim cancelled = sampleDraft();
    cancelled.cancel();
    when(claimService.cancel(eq(CLAIM), eq("k-2"))).thenReturn(cancelled);

    mockMvc
        .perform(
            post("/api/v1/me/expense-claims/" + CLAIM + "/cancel").header("Idempotency-Key", "k-2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(ClaimStatus.CANCELLED.name()));
  }

  @Test
  void getOfAnUnknownOrNotOwnedClaimIs404NotForbidden() throws Exception {
    // Anti-enumeration: unknown id and "not mine" both collapse to 404, never 403.
    when(claimReader.myClaim(CLAIM)).thenThrow(new ClaimNotFoundException(CLAIM));

    mockMvc
        .perform(get("/api/v1/me/expense-claims/" + CLAIM))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/expense-claim-not-found"));
  }

  @Test
  void getReturns200() throws Exception {
    when(claimReader.myClaim(CLAIM)).thenReturn(ExpenseClaimResponse.from(sampleDraft()));

    mockMvc
        .perform(get("/api/v1/me/expense-claims/" + CLAIM))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(ClaimStatus.DRAFT.name()));
  }

  @Test
  void listReturns200WithThePaginationEnvelope() throws Exception {
    when(claimReader.myClaims(any(), any())).thenReturn(PageResponse.of(List.of(), 0, 25, 0));

    mockMvc
        .perform(get("/api/v1/me/expense-claims"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.totalPages").value(0));
  }

  @Test
  void listPassesPageAndSizeQueryParamsThrough() throws Exception {
    when(claimReader.myClaims(eq(2), eq(10))).thenReturn(PageResponse.of(List.of(), 2, 10, 25));

    mockMvc
        .perform(get("/api/v1/me/expense-claims?page=2&size=10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(2))
        .andExpect(jsonPath("$.size").value(10));
  }

  // ---------------------------------------------------------------------
  // Receipt upload/serve (ADR 0030 §8, Phase E3)
  // ---------------------------------------------------------------------

  @Test
  void uploadReceiptReturns201WithMeta() throws Exception {
    when(receiptWriter.upload(eq(CLAIM), eq("image/jpeg"), any())).thenReturn(sampleReceipt());
    MockMultipartFile file =
        new MockMultipartFile("file", "receipt.jpg", "image/jpeg", JPEG_BYTES.clone());

    mockMvc
        .perform(multipart("/api/v1/me/expense-claims/" + CLAIM + "/receipt").file(file))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.claimId").value(CLAIM.toString()))
        .andExpect(jsonPath("$.contentType").value("image/jpeg"))
        .andExpect(jsonPath("$.byteSize").value(JPEG_BYTES.length));
  }

  @Test
  void uploadReceiptOversizeIs413() throws Exception {
    when(receiptWriter.upload(eq(CLAIM), any(), any()))
        .thenThrow(new MaxUploadSizeExceededException(ExpenseReceipt.MAX_BYTES));
    MockMultipartFile file =
        new MockMultipartFile("file", "receipt.jpg", "image/jpeg", JPEG_BYTES.clone());

    mockMvc
        .perform(multipart("/api/v1/me/expense-claims/" + CLAIM + "/receipt").file(file))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/receipt-too-large"));
  }

  @Test
  void uploadReceiptWithASpoofedContentTypeIs422() throws Exception {
    when(receiptWriter.upload(eq(CLAIM), any(), any()))
        .thenThrow(new InvalidReceiptContentTypeException("image/jpeg", "image/png"));
    MockMultipartFile file =
        new MockMultipartFile("file", "receipt.jpg", "image/jpeg", JPEG_BYTES.clone());

    mockMvc
        .perform(multipart("/api/v1/me/expense-claims/" + CLAIM + "/receipt").file(file))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/invalid-receipt-content-type"));
  }

  @Test
  void uploadReceiptToANonDraftClaimIs409() throws Exception {
    when(receiptWriter.upload(eq(CLAIM), any(), any()))
        .thenThrow(new ClaimStateException(ClaimStatus.SUBMITTED, "attach a receipt to"));
    MockMultipartFile file =
        new MockMultipartFile("file", "receipt.jpg", "image/jpeg", JPEG_BYTES.clone());

    mockMvc
        .perform(multipart("/api/v1/me/expense-claims/" + CLAIM + "/receipt").file(file))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/expense-claim-state-conflict"));
  }

  @Test
  void uploadReceiptToSomeoneElsesClaimIs404() throws Exception {
    // Anti-enumeration: an unknown id and "not mine" both collapse to 404 (ExpenseClaimReader's
    // idiom applied to receipts too).
    when(receiptWriter.upload(eq(CLAIM), any(), any()))
        .thenThrow(new ClaimNotFoundException(CLAIM));
    MockMultipartFile file =
        new MockMultipartFile("file", "receipt.jpg", "image/jpeg", JPEG_BYTES.clone());

    mockMvc
        .perform(multipart("/api/v1/me/expense-claims/" + CLAIM + "/receipt").file(file))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/expense-claim-not-found"));
  }

  @Test
  void getReceiptReturns200WithTheBytesAndCorrectHeaders() throws Exception {
    when(receiptReader.myReceipt(CLAIM))
        .thenReturn(new ReceiptContentResponse("image/jpeg", JPEG_BYTES.clone(), "a".repeat(64)));

    mockMvc
        .perform(get("/api/v1/me/expense-claims/" + CLAIM + "/receipt"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/jpeg"))
        .andExpect(header().longValue("Content-Length", JPEG_BYTES.length))
        .andExpect(header().string("ETag", "\"" + "a".repeat(64) + "\""))
        .andExpect(content().bytes(JPEG_BYTES));
  }

  @Test
  void getReceiptWhenNoneOnFileIs404() throws Exception {
    when(receiptReader.myReceipt(CLAIM)).thenThrow(new ReceiptNotFoundException(CLAIM));

    mockMvc
        .perform(get("/api/v1/me/expense-claims/" + CLAIM + "/receipt"))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/expense-receipt-not-found"));
  }
}
