package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.unitpnl.controller.UnitPnlController;
import id.co.nativeapp.finance.unitpnl.dto.UnitPnlResponse;
import id.co.nativeapp.finance.unitpnl.service.UnitPnlReader;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@code GET /api/v1/pnl/org-units/{id}}: 200 with the combined
 * totals+breakdown shape; 204 for an unknown/foreign/team unit; the {@code PnlController}-mirrored
 * empty-period semantics (204 without a currency hint, 200 zeros with one); RFC-7807 400 for a
 * malformed period. The {@link UnitPnlReader} is mocked — no DB.
 */
@WebMvcTest(UnitPnlController.class)
@Import(ConstraintViolationAdvice.class)
class UnitPnlControllerTest {

  private static final UUID UNIT = UUID.fromString("11111111-0000-0000-0000-000000000001");
  private static final UUID OUTLET = UUID.fromString("22222222-0000-0000-0000-000000000002");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private UnitPnlReader unitPnlReader;

  @Test
  void returnsTheCombinedTotalsAndBreakdownShape() throws Exception {
    UnitPnlResponse response =
        new UnitPnlResponse(
            UNIT,
            "2026-07",
            350_000L,
            50_000L,
            300_000L,
            "IDR",
            false,
            List.of(
                new UnitPnlResponse.OutletPnlRow(OUTLET, "Outlet B", true, 250_000L, 0L, 250_000L)));
    when(unitPnlReader.unitPnlForPeriod(UNIT, "2026-07")).thenReturn(Optional.of(response));

    mockMvc
        .perform(get("/api/v1/pnl/org-units/" + UNIT).param("period", "2026-07"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orgUnitId").value(UNIT.toString()))
        .andExpect(jsonPath("$.period").value("2026-07"))
        .andExpect(jsonPath("$.revenueMinor").value(350_000L))
        .andExpect(jsonPath("$.expenseMinor").value(50_000L))
        .andExpect(jsonPath("$.netMinor").value(300_000L))
        .andExpect(jsonPath("$.currency").value("IDR"))
        .andExpect(jsonPath("$.outlets[0].orgUnitId").value(OUTLET.toString()))
        .andExpect(jsonPath("$.outlets[0].name").value("Outlet B"))
        .andExpect(jsonPath("$.outlets[0].netMinor").value(250_000L));
  }

  @Test
  void returns204ForAnUnknownUnit() throws Exception {
    when(unitPnlReader.unitPnlForPeriod(any(), eq("2026-07"))).thenReturn(Optional.empty());

    mockMvc
        .perform(
            get("/api/v1/pnl/org-units/" + UUID.randomUUID())
                .param("period", "2026-07")
                .param("currency", "IDR"))
        .andExpect(status().isNoContent());
  }

  @Test
  void emptyPeriodWithoutCurrencyHintIs204() throws Exception {
    // Known unit, zero postings — reader returns the zeros row with a NULL currency.
    UnitPnlResponse zeros =
        new UnitPnlResponse(UNIT, "2026-08", 0L, 0L, 0L, null, false, List.of());
    when(unitPnlReader.unitPnlForPeriod(UNIT, "2026-08")).thenReturn(Optional.of(zeros));

    mockMvc
        .perform(get("/api/v1/pnl/org-units/" + UNIT).param("period", "2026-08"))
        .andExpect(status().isNoContent());
  }

  @Test
  void emptyPeriodWithCurrencyHintIs200Zeros() throws Exception {
    UnitPnlResponse zeros =
        new UnitPnlResponse(
            UNIT,
            "2026-08",
            0L,
            0L,
            0L,
            null,
            false,
            List.of(new UnitPnlResponse.OutletPnlRow(OUTLET, "Fresh Outlet", true, 0L, 0L, 0L)));
    when(unitPnlReader.unitPnlForPeriod(UNIT, "2026-08")).thenReturn(Optional.of(zeros));

    mockMvc
        .perform(
            get("/api/v1/pnl/org-units/" + UNIT)
                .param("period", "2026-08")
                .param("currency", "IDR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revenueMinor").value(0L))
        .andExpect(jsonPath("$.currency").value("IDR"))
        .andExpect(jsonPath("$.outlets[0].name").value("Fresh Outlet"));
  }

  @Test
  void rejectsAMalformedPeriodWithAProblemDetail400() throws Exception {
    mockMvc
        .perform(get("/api/v1/pnl/org-units/" + UNIT).param("period", "July-2026"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }
}
