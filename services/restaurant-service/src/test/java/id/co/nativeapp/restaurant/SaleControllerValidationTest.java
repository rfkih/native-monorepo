package id.co.nativeapp.restaurant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.sale.controller.SaleController;
import id.co.nativeapp.restaurant.sale.domain.Sale;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.dto.SaleResponse;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Edge input handling for {@code POST /api/v1/sales} (FIX 1), proven as a web slice — no DB.
 *
 * <p>Bean-validation failures on the {@code @Valid} body and a domain {@link
 * IllegalArgumentException} (e.g. an unknown ISO-4217 currency from {@code libs/money}) are mapped
 * to an RFC 7807 {@link org.springframework.http.ProblemDetail} {@code 400} ({@code
 * application/problem+json}) by {@link ApiExceptionHandler}, while a valid request still reaches
 * the service. The assertions pin the ProblemDetail shape: {@code status}, content-type, the stable
 * {@code type} URI, {@code title}, and the machine-readable {@code errors[]} of {@code {field,
 * message}}. A successful create returns {@code 201} with a {@code Location} header at {@code
 * /api/v1/sales/{id}}; the idempotent-retry path is a {@code 200} (no {@code Location}) returned by
 * {@link SaleController} and is unaffected by the advice.
 */
@WebMvcTest(SaleController.class)
@Import(ApiExceptionHandler.class)
class SaleControllerValidationTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String SALES_PATH = "/api/v1/sales";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SaleService saleService;

  @Test
  void recordingANewSaleReturns201WithALocationHeaderUnderApiV1() throws Exception {
    Sale sale =
        new Sale(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            Money.ofMinor(1_500_000L, "IDR"),
            Instant.parse("2026-06-14T08:30:00Z"),
            "k1");
    when(saleService.recordSale(any()))
        .thenReturn(new RecordSaleResult(SaleResponse.from(sale), true));
    String body =
        """
                {"businessId":"22222222-2222-2222-2222-222222222222",
                 "amountMinor":1500000,"currency":"IDR","idempotencyKey":"k1"}
                """;
    mockMvc
        .perform(post(SALES_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/sales/" + sale.getId()))
        .andExpect(jsonPath("$.id").value(sale.getId().toString()));
  }

  @Test
  void anIdempotentRetryReturns200WithNoLocationHeader() throws Exception {
    Sale sale =
        new Sale(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            Money.ofMinor(1_500_000L, "IDR"),
            Instant.parse("2026-06-14T08:30:00Z"),
            "k1");
    when(saleService.recordSale(any()))
        .thenReturn(new RecordSaleResult(SaleResponse.from(sale), false));
    String body =
        """
                {"businessId":"22222222-2222-2222-2222-222222222222",
                 "amountMinor":1500000,"currency":"IDR","idempotencyKey":"k1"}
                """;
    mockMvc
        .perform(post(SALES_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Location"));
  }

  @Test
  void blankCurrencyIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
                {"businessId":"22222222-2222-2222-2222-222222222222",
                 "amountMinor":1500000,"currency":"","idempotencyKey":"k1"}
                """;
    mockMvc
        .perform(post(SALES_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Validation failed"))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[0].field").value("currency"))
        .andExpect(jsonPath("$.errors[0].message").exists());
  }

  @Test
  void nonPositiveAmountIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
                {"businessId":"22222222-2222-2222-2222-222222222222",
                 "amountMinor":0,"currency":"IDR","idempotencyKey":"k1"}
                """;
    mockMvc
        .perform(post(SALES_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"))
        .andExpect(jsonPath("$.errors[0].field").value("amountMinor"));
  }

  @Test
  void missingBusinessIdIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
                {"amountMinor":1500000,"currency":"IDR","idempotencyKey":"k1"}
                """;
    mockMvc
        .perform(post(SALES_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("businessId"));
  }

  @Test
  void unknownCurrencyCodeFromMoneyIsMappedToAProblemDetail() throws Exception {
    // Passes bean validation (non-blank), but Money.ofMinor rejects "ZZZ" with an
    // IllegalArgumentException that the advice maps to a 400 ProblemDetail.
    when(saleService.recordSale(any()))
        .thenThrow(new IllegalArgumentException("No currency for code ZZZ"));
    String body =
        """
                {"businessId":"22222222-2222-2222-2222-222222222222",
                 "amountMinor":1500000,"currency":"ZZZ","idempotencyKey":"k1"}
                """;
    mockMvc
        .perform(post(SALES_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invalid-argument"))
        .andExpect(jsonPath("$.detail").value("No currency for code ZZZ"));
  }
}
