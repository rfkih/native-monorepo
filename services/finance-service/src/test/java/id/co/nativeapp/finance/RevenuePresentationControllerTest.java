package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.fx.domain.MissingFxRateException;
import id.co.nativeapp.finance.fx.dto.AppliedRate;
import id.co.nativeapp.finance.fx.dto.RevenuePresentation;
import id.co.nativeapp.finance.fx.service.PresentationConverter;
import id.co.nativeapp.finance.revenue.controller.RevenueController;
import id.co.nativeapp.finance.revenue.service.RevenueReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.money.Provenance;
import id.co.nativeapp.money.RateType;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for the ADDITIVE {@code presentation} query param on {@code GET /api/v1/revenue}:
 * the native total is still returned alongside the converted presentation fields, the {@code
 * usesStubFx} flag rides the response, and a missing rate fails loudly with a 422. The {@link
 * RevenueReader} and {@link PresentationConverter} are mocked — no DB.
 */
@WebMvcTest(RevenueController.class)
@Import(ConstraintViolationAdvice.class)
class RevenuePresentationControllerTest {

  private static final Currency USD = Currency.getInstance("USD");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private RevenueReader revenueReader;
  @MockitoBean private PresentationConverter presentationConverter;

  @Test
  void presentationParamReturnsConvertedFigureWithNativeTotalStillPresentAndStubFlag()
      throws Exception {
    // Native IDR 16,000,000 revenue; presentation USD -> 1,000.00 (100_000 minor), stub-derived.
    Money nativeTotal = Money.ofMinor(16_000_000L, "IDR");
    when(revenueReader.revenueForPeriod("2026-06")).thenReturn(Optional.of(nativeTotal));

    AppliedRate applied =
        new AppliedRate(
            "IDR", "USD", RateType.AVERAGE, 629L, 7, Provenance.STUB, "2000-01-01", null);
    RevenuePresentation view =
        new RevenuePresentation(
            Money.ofMinor(100_000L, "USD"), true, LocalDate.parse("2026-06-01"), List.of(applied));
    when(presentationConverter.convertRevenue(eq(nativeTotal), eq("2026-06"), any(Currency.class)))
        .thenReturn(view);

    mockMvc
        .perform(get("/api/v1/revenue").param("period", "2026-06").param("presentation", "USD"))
        .andExpect(status().isOk())
        // Native total STILL returned alongside (presentation is additive, never a replacement).
        .andExpect(jsonPath("$.totalMinor").value(16_000_000L))
        .andExpect(jsonPath("$.currency").value("IDR"))
        // Converted presentation fields.
        .andExpect(jsonPath("$.presentationCurrency").value("USD"))
        .andExpect(jsonPath("$.presentationTotalMinor").value(100_000L))
        .andExpect(jsonPath("$.usesStubFx").value(true))
        .andExpect(jsonPath("$.fxAsOf").value("2026-06-01"))
        .andExpect(jsonPath("$.appliedRates[0].fromCcy").value("IDR"))
        .andExpect(jsonPath("$.appliedRates[0].toCcy").value("USD"))
        .andExpect(jsonPath("$.appliedRates[0].rateType").value("AVERAGE"))
        .andExpect(jsonPath("$.appliedRates[0].rateUnscaled").value(629L))
        .andExpect(jsonPath("$.appliedRates[0].rateScale").value(7))
        .andExpect(jsonPath("$.appliedRates[0].provenance").value("STUB"));
  }

  @Test
  void noPresentationParamOmitsAllPresentationFields() throws Exception {
    when(revenueReader.revenueForPeriod("2026-06"))
        .thenReturn(Optional.of(Money.ofMinor(16_000_000L, "IDR")));

    mockMvc
        .perform(get("/api/v1/revenue").param("period", "2026-06"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalMinor").value(16_000_000L))
        // Additive fields are absent (NON_NULL) so old clients see exactly today's shape.
        .andExpect(jsonPath("$.presentationCurrency").doesNotExist())
        .andExpect(jsonPath("$.presentationTotalMinor").doesNotExist())
        .andExpect(jsonPath("$.usesStubFx").doesNotExist());
  }

  @Test
  void aMissingRateFailsLoudlyWith422AndNeverReturnsTheNativeFigureMislabelled() throws Exception {
    Money nativeTotal = Money.ofMinor(16_000_000L, "IDR");
    when(revenueReader.revenueForPeriod("2026-06")).thenReturn(Optional.of(nativeTotal));
    when(presentationConverter.convertRevenue(eq(nativeTotal), eq("2026-06"), any(Currency.class)))
        .thenThrow(
            new MissingFxRateException(
                Currency.getInstance("IDR"), USD, LocalDate.parse("2026-06-01")));

    mockMvc
        .perform(get("/api/v1/revenue").param("period", "2026-06").param("presentation", "USD"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/fx-rate-unavailable"));
  }
}
