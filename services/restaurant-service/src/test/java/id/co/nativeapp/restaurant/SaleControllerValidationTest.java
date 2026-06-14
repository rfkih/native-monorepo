package id.co.nativeapp.restaurant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Edge input handling for {@code POST /sales} (FIX 1), proven as a web slice — no DB.
 *
 * <p>Bean-validation failures on the {@code @Valid} body and a domain {@link
 * IllegalArgumentException} (e.g. an unknown ISO-4217 currency from {@code libs/money}) are mapped
 * to {@code 400} by {@link ApiExceptionHandler}, while a valid request still reaches the service.
 * The idempotent-retry path is a {@code 200} returned by {@link SaleController} and is unaffected
 * by the advice.
 */
@WebMvcTest(SaleController.class)
class SaleControllerValidationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SaleService saleService;

  @Test
  void blankCurrencyIsRejectedWith400() throws Exception {
    String body =
        """
                {"businessId":"22222222-2222-2222-2222-222222222222",
                 "amountMinor":1500000,"currency":"","idempotencyKey":"k1"}
                """;
    mockMvc
        .perform(post("/sales").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void nonPositiveAmountIsRejectedWith400() throws Exception {
    String body =
        """
                {"businessId":"22222222-2222-2222-2222-222222222222",
                 "amountMinor":0,"currency":"IDR","idempotencyKey":"k1"}
                """;
    mockMvc
        .perform(post("/sales").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void missingBusinessIdIsRejectedWith400() throws Exception {
    String body =
        """
                {"amountMinor":1500000,"currency":"IDR","idempotencyKey":"k1"}
                """;
    mockMvc
        .perform(post("/sales").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownCurrencyCodeFromMoneyIsMappedTo400() throws Exception {
    // Passes bean validation (non-blank), but Money.ofMinor rejects "ZZZ" with an
    // IllegalArgumentException that the advice maps to 400 (not a 500).
    when(saleService.recordSale(any()))
        .thenThrow(new IllegalArgumentException("No currency for code ZZZ"));
    String body =
        """
                {"businessId":"22222222-2222-2222-2222-222222222222",
                 "amountMinor":1500000,"currency":"ZZZ","idempotencyKey":"k1"}
                """;
    mockMvc
        .perform(post("/sales").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("No currency for code ZZZ"));
  }
}
