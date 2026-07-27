package id.co.nativeapp.finance;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.outlet.controller.OutletRevenueController;
import id.co.nativeapp.finance.outlet.dto.OutletRevenueResponse;
import id.co.nativeapp.finance.outlet.service.OutletRevenueReader;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@code GET /api/v1/pnl/outlets}: a 200 with per-outlet rows when data exists,
 * a 204 when no data, and an RFC-7807 400 for a malformed period. The {@link OutletRevenueReader}
 * is mocked — no DB.
 */
@WebMvcTest(OutletRevenueController.class)
@Import(ConstraintViolationAdvice.class)
class OutletRevenueControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OutletRevenueReader outletRevenueReader;

  @Test
  void returnsOutletRowsOrderedByRevenueDesc() throws Exception {
    UUID outlet1 = UUID.fromString("11111111-0000-0000-0000-000000000001");
    UUID outlet2 = UUID.fromString("22222222-0000-0000-0000-000000000002");

    OutletRevenueResponse response =
        new OutletRevenueResponse(
            "2026-07",
            "IDR",
            List.of(
                new OutletRevenueResponse.OutletRow(outlet1, 300_000L, "Main Outlet"),
                new OutletRevenueResponse.OutletRow(outlet2, 100_000L, null)));

    when(outletRevenueReader.outletsForPeriod("2026-07")).thenReturn(Optional.of(response));

    mockMvc
        .perform(get("/api/v1/pnl/outlets").param("period", "2026-07"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.period").value("2026-07"))
        .andExpect(jsonPath("$.currency").value("IDR"))
        .andExpect(jsonPath("$.outlets[0].businessId").value(outlet1.toString()))
        .andExpect(jsonPath("$.outlets[0].revenueMinor").value(300_000L))
        .andExpect(jsonPath("$.outlets[0].outletName").value("Main Outlet"))
        .andExpect(jsonPath("$.outlets[1].businessId").value(outlet2.toString()))
        .andExpect(jsonPath("$.outlets[1].revenueMinor").value(100_000L))
        .andExpect(jsonPath("$.outlets[1].outletName").doesNotExist());
  }

  @Test
  void returns204WhenNoPeriodData() throws Exception {
    when(outletRevenueReader.outletsForPeriod("2026-08")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/pnl/outlets").param("period", "2026-08"))
        .andExpect(status().isNoContent());
  }

  @Test
  void rejectsAMalformedPeriodWithAProblemDetail400() throws Exception {
    mockMvc
        .perform(get("/api/v1/pnl/outlets").param("period", "July-2026"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-13", "2026-00"})
  void rejectsAnImpossibleMonthWithAProblemDetail400(String impossiblePeriod) throws Exception {
    mockMvc
        .perform(get("/api/v1/pnl/outlets").param("period", impossiblePeriod))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }
}
