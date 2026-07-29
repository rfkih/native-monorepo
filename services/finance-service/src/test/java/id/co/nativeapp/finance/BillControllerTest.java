package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.ap.controller.ApAdvice;
import id.co.nativeapp.finance.ap.controller.BillController;
import id.co.nativeapp.finance.ap.domain.BillNotFoundException;
import id.co.nativeapp.finance.ap.domain.BillStateException;
import id.co.nativeapp.finance.ap.dto.BillDetailResponse;
import id.co.nativeapp.finance.ap.service.BillPaymentWriter;
import id.co.nativeapp.finance.ap.service.BillReader;
import id.co.nativeapp.finance.ap.service.BillWriter;
import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link BillController}: the HTTP contract of the AP bill endpoints — a 201 on
 * create, a 200 on post/pay, a 400 (RFC-7807) for a request the bean-validation layer rejects, a
 * 404 for an unknown bill, and a 409 for an illegal lifecycle transition. The writers + reader are
 * mocked (no DB); the fault mappings come from the imported advices ({@link ApAdvice} owns the AP
 * 404/409/400; the shared {@link ApiExceptionHandler} owns the {@code @Valid}-body 400). The mirror
 * of {@link InvoiceControllerTest}.
 */
@WebMvcTest(BillController.class)
@Import({ApAdvice.class, ApiExceptionHandler.class, ConstraintViolationAdvice.class})
class BillControllerTest {

  private static final UUID BILL = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  private static final UUID VENDOR = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BillWriter billWriter;
  @MockitoBean private BillPaymentWriter billPaymentWriter;
  @MockitoBean private BillReader billReader;

  private static BillDetailResponse sampleDetail() {
    return new BillDetailResponse(
        BILL,
        "BILL-00001",
        VENDOR,
        "Acme Supplies",
        "POSTED",
        LocalDate.parse("2026-06-14"),
        LocalDate.parse("2026-07-14"),
        "IDR",
        1_000_000L,
        110_000L,
        1_110_000L,
        0L,
        1_110_000L,
        true,
        List.of(),
        List.of());
  }

  @Test
  void createReturns201WithTheBillDetail() throws Exception {
    when(billWriter.createDraft(eq(VENDOR), eq("IDR"), eq(true), any())).thenReturn(BILL);
    when(billReader.detail(BILL)).thenReturn(sampleDetail());

    mockMvc
        .perform(
            post("/api/v1/ap/bills")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"vendorId\":\""
                        + VENDOR
                        + "\",\"currency\":\"IDR\",\"taxable\":true,"
                        + "\"lines\":[{\"description\":\"Supplies\",\"quantity\":2,"
                        + "\"unitPriceMinor\":500000}]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(BILL.toString()))
        .andExpect(jsonPath("$.status").value("POSTED"))
        .andExpect(jsonPath("$.totalMinor").value(1_110_000L))
        .andExpect(jsonPath("$.usesIllustrativeRules").value(true));
  }

  @Test
  void createWithNoLinesIsAProblemDetail400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/ap/bills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vendorId\":\"" + VENDOR + "\",\"currency\":\"IDR\",\"lines\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void getUnknownBillIsAProblemDetail404() throws Exception {
    when(billReader.detail(BILL)).thenThrow(new BillNotFoundException(BILL));

    mockMvc
        .perform(get("/api/v1/ap/bills/{id}", BILL))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/ap-not-found"));
  }

  @Test
  void postingANonDraftIsAProblemDetail409() throws Exception {
    when(billWriter.post(eq(BILL), isNull()))
        .thenThrow(new BillStateException("only a DRAFT bill can be posted; status=POSTED"));

    mockMvc
        .perform(post("/api/v1/ap/bills/{id}/post", BILL))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/bill-invalid-state"));
  }

  @Test
  void postReturns200WithTheUpdatedDetail() throws Exception {
    when(billWriter.post(eq(BILL), isNull())).thenReturn(BILL);
    when(billReader.detail(BILL)).thenReturn(sampleDetail());

    mockMvc
        .perform(post("/api/v1/ap/bills/{id}/post", BILL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.billNumber").value("BILL-00001"));
  }

  @Test
  void overpaymentIsAProblemDetail409() throws Exception {
    when(billPaymentWriter.record(eq(BILL), anyLong(), isNull(), any()))
        .thenThrow(new BillStateException("payment exceeds the outstanding balance"));

    mockMvc
        .perform(
            post("/api/v1/ap/bills/{id}/payments", BILL)
                .header("Idempotency-Key", "test-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":999999999}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/bill-invalid-state"));
  }

  @Test
  void paymentWithoutIdempotencyKeyIsBadRequest() throws Exception {
    // The Idempotency-Key header is REQUIRED (mirroring AR's code-review C-2) — a keyless payment
    // is rejected.
    mockMvc
        .perform(
            post("/api/v1/ap/bills/{id}/payments", BILL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":100000}"))
        .andExpect(status().isBadRequest());
  }
}
