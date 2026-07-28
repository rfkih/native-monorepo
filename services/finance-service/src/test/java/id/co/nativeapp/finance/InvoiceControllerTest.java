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

import id.co.nativeapp.finance.ar.controller.ArAdvice;
import id.co.nativeapp.finance.ar.controller.InvoiceController;
import id.co.nativeapp.finance.ar.domain.InvoiceNotFoundException;
import id.co.nativeapp.finance.ar.domain.InvoiceStateException;
import id.co.nativeapp.finance.ar.dto.InvoiceDetailResponse;
import id.co.nativeapp.finance.ar.service.InvoiceReader;
import id.co.nativeapp.finance.ar.service.InvoiceWriter;
import id.co.nativeapp.finance.ar.service.PaymentWriter;
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
 * Web-slice test for {@link InvoiceController}: the HTTP contract of the AR invoice endpoints — a
 * 201 on create, a 200 on issue/pay, a 400 (RFC-7807) for a request the bean-validation layer
 * rejects, a 404 for an unknown invoice, and a 409 for an illegal lifecycle transition. The writers
 * + reader are mocked (no DB); the fault mappings come from the imported advices ({@link ArAdvice}
 * owns the AR 404/409/400; the shared {@link ApiExceptionHandler} owns the {@code @Valid}-body
 * 400).
 */
@WebMvcTest(InvoiceController.class)
@Import({ArAdvice.class, ApiExceptionHandler.class, ConstraintViolationAdvice.class})
class InvoiceControllerTest {

  private static final UUID INVOICE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  private static final UUID CUSTOMER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private InvoiceWriter invoiceWriter;
  @MockitoBean private PaymentWriter paymentWriter;
  @MockitoBean private InvoiceReader invoiceReader;

  private static InvoiceDetailResponse sampleDetail() {
    return new InvoiceDetailResponse(
        INVOICE,
        "INV-00001",
        CUSTOMER,
        "Acme Corp",
        "ISSUED",
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
  void createReturns201WithTheInvoiceDetail() throws Exception {
    when(invoiceWriter.createDraft(eq(CUSTOMER), eq("IDR"), eq(true), any())).thenReturn(INVOICE);
    when(invoiceReader.detail(INVOICE)).thenReturn(sampleDetail());

    mockMvc
        .perform(
            post("/api/v1/invoices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"customerId\":\""
                        + CUSTOMER
                        + "\",\"currency\":\"IDR\",\"taxable\":true,"
                        + "\"lines\":[{\"description\":\"Consulting\",\"quantity\":2,"
                        + "\"unitPriceMinor\":500000}]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(INVOICE.toString()))
        .andExpect(jsonPath("$.status").value("ISSUED"))
        .andExpect(jsonPath("$.totalMinor").value(1_110_000L))
        .andExpect(jsonPath("$.usesIllustrativeRules").value(true));
  }

  @Test
  void createWithNoLinesIsAProblemDetail400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/invoices")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + CUSTOMER + "\",\"currency\":\"IDR\",\"lines\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void getUnknownInvoiceIsAProblemDetail404() throws Exception {
    when(invoiceReader.detail(INVOICE)).thenThrow(new InvoiceNotFoundException(INVOICE));

    mockMvc
        .perform(get("/api/v1/invoices/{id}", INVOICE))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/ar-not-found"));
  }

  @Test
  void issuingANonDraftIsAProblemDetail409() throws Exception {
    when(invoiceWriter.issue(eq(INVOICE), isNull()))
        .thenThrow(new InvoiceStateException("only a DRAFT invoice can be issued; status=ISSUED"));

    mockMvc
        .perform(post("/api/v1/invoices/{id}/issue", INVOICE))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invoice-invalid-state"));
  }

  @Test
  void issueReturns200WithTheUpdatedDetail() throws Exception {
    when(invoiceWriter.issue(eq(INVOICE), isNull())).thenReturn(INVOICE);
    when(invoiceReader.detail(INVOICE)).thenReturn(sampleDetail());

    mockMvc
        .perform(post("/api/v1/invoices/{id}/issue", INVOICE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.invoiceNumber").value("INV-00001"));
  }

  @Test
  void overpaymentIsAProblemDetail409() throws Exception {
    when(paymentWriter.record(eq(INVOICE), anyLong(), isNull(), any()))
        .thenThrow(new InvoiceStateException("payment exceeds the outstanding balance"));

    mockMvc
        .perform(
            post("/api/v1/invoices/{id}/payments", INVOICE)
                .header("Idempotency-Key", "test-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":999999999}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invoice-invalid-state"));
  }

  @Test
  void paymentWithoutIdempotencyKeyIsBadRequest() throws Exception {
    // The Idempotency-Key header is REQUIRED (code-review C-2) — a keyless payment is rejected.
    mockMvc
        .perform(
            post("/api/v1/invoices/{id}/payments", INVOICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":100000}"))
        .andExpect(status().isBadRequest());
  }
}
