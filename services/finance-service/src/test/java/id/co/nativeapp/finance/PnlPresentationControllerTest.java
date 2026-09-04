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
import id.co.nativeapp.finance.fx.dto.PnlPresentation;
import id.co.nativeapp.finance.fx.service.PresentationConverter;
import id.co.nativeapp.finance.pnl.controller.PnlController;
import id.co.nativeapp.finance.pnl.domain.PnlFigures;
import id.co.nativeapp.finance.pnl.service.PnlReader;
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
 * Web-slice test for the ADDITIVE {@code presentation} query param on {@code GET /api/v1/pnl}: each
 * leg is converted, the native figures are still returned alongside, the OR-ed {@code usesStubFx}
 * flag rides the response, and a missing rate fails loudly with a 422 (atomic — never a
 * half-converted P&amp;L). The {@link PnlReader} and {@link PresentationConverter} are mocked.
 */
@WebMvcTest(PnlController.class)
@Import(ConstraintViolationAdvice.class)
class PnlPresentationControllerTest {

  private static final Currency USD = Currency.getInstance("USD");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PnlReader pnlReader;
  @MockitoBean private PresentationConverter presentationConverter;

  private static PnlFigures idrPnl() {
    // net() is derived on the carrier: 20,000,000 − 8,000,000 = 12,000,000 IDR.
    return new PnlFigures(
        "2026-06", Money.ofMinor(20_000_000L, "IDR"), Money.ofMinor(8_000_000L, "IDR"), false);
  }

  @Test
  void presentationParamReturnsConvertedLegsWithNativeFiguresStillPresent() throws Exception {
    PnlFigures pnl = idrPnl();
    when(pnlReader.pnlForPeriod("2026-06")).thenReturn(Optional.of(pnl));

    AppliedRate applied =
        new AppliedRate(
            "IDR", "USD", RateType.AVERAGE, 629L, 7, Provenance.STUB, "2000-01-01", null);
    PnlPresentation view =
        new PnlPresentation(
            Money.ofMinor(125_800L, "USD"), // revenue 20,000,000 IDR -> ~1,258.00 USD
            Money.ofMinor(50_320L, "USD"), // expense 8,000,000 IDR -> ~503.20 USD
            Money.ofMinor(75_480L, "USD"), // net in USD
            true,
            LocalDate.parse("2026-06-01"),
            List.of(applied));
    when(presentationConverter.convertPnl(eq(pnl), eq("2026-06"), any(Currency.class)))
        .thenReturn(view);

    mockMvc
        .perform(get("/api/v1/pnl").param("period", "2026-06").param("presentation", "USD"))
        .andExpect(status().isOk())
        // Native figures STILL present.
        .andExpect(jsonPath("$.revenueMinor").value(20_000_000L))
        .andExpect(jsonPath("$.expenseMinor").value(8_000_000L))
        .andExpect(jsonPath("$.netMinor").value(12_000_000L))
        .andExpect(jsonPath("$.currency").value("IDR"))
        // Converted presentation legs + derived net + flags.
        .andExpect(jsonPath("$.presentationCurrency").value("USD"))
        .andExpect(jsonPath("$.presentationRevenueMinor").value(125_800L))
        .andExpect(jsonPath("$.presentationExpenseMinor").value(50_320L))
        .andExpect(jsonPath("$.presentationNetMinor").value(75_480L))
        .andExpect(jsonPath("$.usesStubFx").value(true))
        .andExpect(jsonPath("$.fxAsOf").value("2026-06-01"));
  }

  @Test
  void noPresentationParamOmitsAllPresentationFields() throws Exception {
    PnlFigures pnl = idrPnl();
    when(pnlReader.pnlForPeriod("2026-06")).thenReturn(Optional.of(pnl));

    mockMvc
        .perform(get("/api/v1/pnl").param("period", "2026-06"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revenueMinor").value(20_000_000L))
        .andExpect(jsonPath("$.presentationCurrency").doesNotExist())
        .andExpect(jsonPath("$.presentationNetMinor").doesNotExist())
        .andExpect(jsonPath("$.usesStubFx").doesNotExist());
  }

  @Test
  void aMissingRateFailsLoudlyWith422() throws Exception {
    PnlFigures pnl = idrPnl();
    when(pnlReader.pnlForPeriod("2026-06")).thenReturn(Optional.of(pnl));
    when(presentationConverter.convertPnl(eq(pnl), eq("2026-06"), any(Currency.class)))
        .thenThrow(
            new MissingFxRateException(
                Currency.getInstance("IDR"), USD, LocalDate.parse("2026-06-01")));

    mockMvc
        .perform(get("/api/v1/pnl").param("period", "2026-06").param("presentation", "USD"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/fx-rate-unavailable"));
  }
}
