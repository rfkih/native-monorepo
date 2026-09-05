package id.co.nativeapp.restaurant.integrity.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.restaurant.config.SalesIntegrityAdvice;
import id.co.nativeapp.restaurant.integrity.domain.LeakSeverity;
import id.co.nativeapp.restaurant.integrity.domain.LeakSignalType;
import id.co.nativeapp.restaurant.integrity.domain.MixedCurrencyLeakReportException;
import id.co.nativeapp.restaurant.integrity.dto.LeakCoverageResponse;
import id.co.nativeapp.restaurant.integrity.dto.LeakDetailResponse;
import id.co.nativeapp.restaurant.integrity.dto.LeakSignalResponse;
import id.co.nativeapp.restaurant.integrity.dto.SalesIntegrityReportResponse;
import id.co.nativeapp.restaurant.integrity.service.SalesIntegrityReader;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link SalesIntegrityController} + {@link SalesIntegrityAdvice} — the HTTP
 * contract the service-level integration test cannot see.
 *
 * <p>Two things matter here beyond the happy path. An inverted window must be REFUSED rather than
 * silently answered with an empty report, because "nothing found" and "you asked a nonsensical
 * question" would look identical to a reader. And a mixed-currency report must surface as a 422 the
 * client can explain, not a 500 — the request is fine, the data is not.
 */
@WebMvcTest(SalesIntegrityController.class)
// ApiExceptionHandler is what maps the controller's IllegalArgumentException to a 400 in
// production; without it the slice would see a raw 500 and this test would be asserting
// something the real service does not do.
@Import({SalesIntegrityAdvice.class, ApiExceptionHandler.class})
class SalesIntegrityControllerTest {

  private static final String BASE = "/api/v1/sales-integrity/report";
  private static final String PROBLEM_JSON = "application/problem+json";
  private static final UUID OUTLET = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final String FROM = "2026-09-01T00:00:00Z";
  private static final String TO = "2026-09-06T00:00:00Z";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private SalesIntegrityReader reader;

  @Test
  void aReportIsReturnedAsMachineSignalsAndIntegerMinorUnits() throws Exception {
    when(reader.report(any(), any(), any())).thenReturn(sampleReport());

    mockMvc
        .perform(
            get(BASE).param("businessId", OUTLET.toString()).param("from", FROM).param("to", TO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estimatedLeakMinorLow").value(30000))
        .andExpect(jsonPath("$.estimatedLeakMinorHigh").value(105000))
        .andExpect(jsonPath("$.confirmedMissingCostMinor").value(6000))
        .andExpect(jsonPath("$.currency").value("IDR"))
        // The signal is a machine enum, never a rendered phrase: the console owns every word the
        // owner reads, in both locales (rule 9).
        .andExpect(jsonPath("$.signals[0].type").value("MISSING_TRACKED_ITEMS"))
        .andExpect(jsonPath("$.signals[0].severity").value("HIGH"))
        .andExpect(jsonPath("$.signals[0].details[0].subjectName").value("Teh Botol"))
        // Coverage rides along with the estimate rather than behind a second request, so a client
        // cannot render the number without the caveat.
        .andExpect(jsonPath("$.coverage.totalSoldQty").value(10))
        .andExpect(jsonPath("$.coverage.recipeBackedSoldQty").value(3))
        .andExpect(jsonPath("$.coverage.daysSinceIngredientCount").doesNotExist());
  }

  @Test
  void anInvertedWindowIsRefusedRatherThanAnsweredWithAnEmptyReport() throws Exception {
    mockMvc
        .perform(
            get(BASE).param("businessId", OUTLET.toString()).param("from", TO).param("to", FROM))
        .andExpect(status().isBadRequest());

    verify(reader, never()).report(any(), any(), any());
  }

  @Test
  void aZeroLengthWindowIsAlsoRefused() throws Exception {
    mockMvc
        .perform(
            get(BASE).param("businessId", OUTLET.toString()).param("from", FROM).param("to", FROM))
        .andExpect(status().isBadRequest());

    verify(reader, never()).report(any(), any(), any());
  }

  // NOT asserted here: a MISSING required @RequestParam currently falls through to
  // ApiExceptionHandler's catch-all and returns 500 rather than 400. That is fleet-wide behaviour
  // (every controller with a required param behaves the same way, e.g. GET /api/v1/sales), not
  // something this endpoint introduces, and fixing it belongs in libs/security where it would
  // change every service's error contract at once. Pinning it here would either codify the wart or
  // make this one endpoint diverge from the rest.

  @Test
  void mixedCurrencyDataSurfacesAsAnExplainable422NotA500() throws Exception {
    when(reader.report(any(), any(), any()))
        .thenThrow(new MixedCurrencyLeakReportException("IDR", "USD"));

    mockMvc
        .perform(
            get(BASE).param("businessId", OUTLET.toString()).param("from", FROM).param("to", TO))
        .andExpect(status().is(422))
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/sales-integrity-mixed-currency"))
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("USD")));
  }

  private static SalesIntegrityReportResponse sampleReport() {
    return new SalesIntegrityReportResponse(
        OUTLET,
        Instant.parse(FROM),
        Instant.parse(TO),
        "IDR",
        30_000L,
        105_000L,
        6_000L,
        List.of(
            new LeakSignalResponse(
                LeakSignalType.MISSING_TRACKED_ITEMS,
                LeakSeverity.HIGH,
                2L,
                30_000L,
                "IDR",
                List.of(
                    new LeakDetailResponse(
                        UUID.randomUUID(), "Teh Botol", null, null, 2L, 30_000L, "IDR")))),
        new LeakCoverageResponse(10L, 3L, null, null, 0L));
  }
}
