package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.tax.controller.TaxAdvice;
import id.co.nativeapp.finance.tax.controller.TaxController;
import id.co.nativeapp.finance.tax.domain.NoVatActivityException;
import id.co.nativeapp.finance.tax.domain.TaxFilingNotFoundException;
import id.co.nativeapp.finance.tax.domain.TaxFilingStateException;
import id.co.nativeapp.finance.tax.dto.EfakturExportResponse;
import id.co.nativeapp.finance.tax.dto.EfakturLine;
import id.co.nativeapp.finance.tax.dto.TaxFilingResponse;
import id.co.nativeapp.finance.tax.dto.VatReturnResponse;
import id.co.nativeapp.finance.tax.service.FileReturnResult;
import id.co.nativeapp.finance.tax.service.TaxFilingReader;
import id.co.nativeapp.finance.tax.service.TaxFilingService;
import id.co.nativeapp.finance.tax.service.VatReturnReader;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.Instant;
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
 * Web-slice test for {@link TaxController}: the VAT report (200), filing (201 + Location), a filed
 * return / history / detail, settle (200), and the fault mappings from {@link TaxAdvice} + the shared
 * {@link ApiExceptionHandler} — 400 (no activity / bad period), 404 (unknown filing), 409 (re-settle),
 * 422 (currency mismatch). Services mocked. Mirrors {@link BankControllerTest}.
 */
@WebMvcTest(TaxController.class)
@Import({TaxAdvice.class, ApiExceptionHandler.class, ConstraintViolationAdvice.class})
class TaxControllerTest {

  private static final UUID FILING = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000007");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private VatReturnReader vatReturnReader;
  @MockitoBean private TaxFilingService taxFilingService;
  @MockitoBean private TaxFilingReader taxFilingReader;

  private static TaxFilingResponse sampleFiling(String status) {
    return new TaxFilingResponse(
        FILING,
        "2026-07",
        "PPN",
        status,
        "IDR",
        1_100_000L,
        440_000L,
        660_000L,
        "PAYABLE",
        UUID.randomUUID(),
        "SETTLED".equals(status) ? UUID.randomUUID() : null,
        Instant.parse("2026-07-31T00:00:00Z"),
        "SETTLED".equals(status) ? Instant.parse("2026-08-01T00:00:00Z") : null);
  }

  @Test
  void vatReturnReturns200() throws Exception {
    when(vatReturnReader.read("2026-07"))
        .thenReturn(
            new VatReturnResponse(
                "2026-07", "IDR", 1_100_000L, 440_000L, 660_000L, "PAYABLE", false, null, null));

    mockMvc
        .perform(get("/api/v1/tax/vat/return").param("period", "2026-07"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outputVatMinor").value(1_100_000L))
        .andExpect(jsonPath("$.inputVatMinor").value(440_000L))
        .andExpect(jsonPath("$.netMinor").value(660_000L))
        .andExpect(jsonPath("$.netDirection").value("PAYABLE"))
        .andExpect(jsonPath("$.filed").value(false));
  }

  @Test
  void vatReturnWithBadPeriodIsA400() throws Exception {
    mockMvc
        .perform(get("/api/v1/tax/vat/return").param("period", "2026-13"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void fileReturns201WithLocationAndDetail() throws Exception {
    when(taxFilingService.file("2026-07")).thenReturn(new FileReturnResult(FILING, true));
    when(taxFilingReader.detail(FILING)).thenReturn(sampleFiling("FILED"));

    mockMvc
        .perform(
            post("/api/v1/tax/vat/returns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"2026-07\"}"))
        .andExpect(status().isCreated())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("PAYABLE")))
        .andExpect(jsonPath("$.status").value("FILED"))
        .andExpect(jsonPath("$.netMinor").value(660_000L));
  }

  @Test
  void refileReturns200WithTheExistingResource() throws Exception {
    // Idempotent re-file: created == false → 200 OK (not 201), the existing (possibly SETTLED) filing.
    when(taxFilingService.file("2026-07")).thenReturn(new FileReturnResult(FILING, false));
    when(taxFilingReader.detail(FILING)).thenReturn(sampleFiling("SETTLED"));

    mockMvc
        .perform(
            post("/api/v1/tax/vat/returns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"2026-07\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"));
  }

  @Test
  void fileAPeriodWithNoVatActivityIsA400() throws Exception {
    when(taxFilingService.file("2026-07"))
        .thenThrow(new NoVatActivityException("period 2026-07 has no VAT activity to file"));

    mockMvc
        .perform(
            post("/api/v1/tax/vat/returns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"2026-07\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/tax-no-activity"));
  }

  @Test
  void fileWithADivergentCurrencyIsA422() throws Exception {
    when(taxFilingService.file("2026-07"))
        .thenThrow(new MismatchedPostingCurrencyException("2026-07", "IDR", "USD"));

    mockMvc
        .perform(
            post("/api/v1/tax/vat/returns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"2026-07\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/tax-currency-mismatch"));
  }

  @Test
  void fileWithABadPeriodBodyIsA400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/tax/vat/returns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"nope\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void settleReturns200WithTheSettledFiling() throws Exception {
    when(taxFilingService.settle(FILING)).thenReturn(FILING);
    when(taxFilingReader.detail(FILING)).thenReturn(sampleFiling("SETTLED"));

    mockMvc
        .perform(post("/api/v1/tax/vat/returns/{id}/settle", FILING))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"));
  }

  @Test
  void settleAnAlreadySettledReturnIsA409() throws Exception {
    when(taxFilingService.settle(FILING))
        .thenThrow(new TaxFilingStateException("tax filing is already settled: " + FILING));

    mockMvc
        .perform(post("/api/v1/tax/vat/returns/{id}/settle", FILING))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/tax-invalid-state"));
  }

  @Test
  void unknownFilingIsA404() throws Exception {
    when(taxFilingReader.detail(any())).thenThrow(new TaxFilingNotFoundException(FILING));

    mockMvc
        .perform(get("/api/v1/tax/vat/returns/{id}", FILING))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/tax-not-found"));
  }

  @Test
  void efakturReturnsTheOutputInvoices() throws Exception {
    when(taxFilingReader.efaktur("2026-07"))
        .thenReturn(
            new EfakturExportResponse(
                "2026-07",
                List.of(
                    new EfakturLine(
                        "INV-00001",
                        LocalDate.parse("2026-07-15"),
                        "Acme Sales",
                        "01.234.567.8-901.000",
                        10_000_000L,
                        1_100_000L,
                        11_100_000L,
                        "IDR"))));

    mockMvc
        .perform(get("/api/v1/tax/vat/efaktur").param("period", "2026-07"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.period").value("2026-07"))
        .andExpect(jsonPath("$.lines[0].invoiceNumber").value("INV-00001"))
        .andExpect(jsonPath("$.lines[0].ppnMinor").value(1_100_000L));
  }
}
