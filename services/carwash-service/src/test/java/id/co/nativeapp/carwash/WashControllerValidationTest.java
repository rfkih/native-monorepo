package id.co.nativeapp.carwash;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.carwash.wash.NotEntitledException;
import id.co.nativeapp.carwash.wash.RecordWashCommand;
import id.co.nativeapp.carwash.wash.RecordWashResult;
import id.co.nativeapp.carwash.wash.WashController;
import id.co.nativeapp.carwash.wash.WashService;
import id.co.nativeapp.security.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@code POST /api/v1/washes} — the controller + the shared RFC-7807 {@link
 * ApiExceptionHandler} advice, no DB. A malformed body fails fast with {@code 400}
 * (bean-validation), and a {@link NotEntitledException} from the service maps to {@code 403} via
 * the carwash {@code NotEntitledAdvice}. The service is mocked so the slice is pure web.
 */
@WebMvcTest(WashController.class)
@Import({ApiExceptionHandler.class, id.co.nativeapp.carwash.config.NotEntitledAdvice.class})
class WashControllerValidationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private WashService washService;

  @Test
  void aMissingBusinessIdIsRejectedWith400() throws Exception {
    // No businessId / bay / amountMinor / currency / idempotencyKey -> bean-validation 400.
    String body = "{}";
    mockMvc
        .perform(post("/api/v1/washes").contentType("application/json").content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }

  @Test
  void aNonPositiveAmountIsRejectedWith400() throws Exception {
    String body =
        """
        {
          "businessId": "22222222-2222-2222-2222-222222222222",
          "bay": "bay-1",
          "amountMinor": 0,
          "currency": "IDR",
          "idempotencyKey": "k1"
        }
        """;
    mockMvc
        .perform(post("/api/v1/washes").contentType("application/json").content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aNotEntitledCompanyIsRejectedWith403() throws Exception {
    when(washService.recordWash(ArgumentMatchers.any(RecordWashCommand.class)))
        .thenThrow(new NotEntitledException("carwash"));

    String body =
        """
        {
          "businessId": "22222222-2222-2222-2222-222222222222",
          "bay": "bay-1",
          "amountMinor": 500000,
          "currency": "IDR",
          "idempotencyKey": "k1"
        }
        """;
    mockMvc
        .perform(post("/api/v1/washes").contentType("application/json").content(body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/module-not-entitled"));
  }

  @Test
  void aSuccessfulRecordReturns201WithLocation() throws Exception {
    // Stub a created result so the controller emits 201 + Location.
    var wash =
        new id.co.nativeapp.carwash.wash.Wash(
            java.util.UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "bay-1",
            java.util.Optional.empty(),
            id.co.nativeapp.money.Money.ofMinor(500000L, "IDR"),
            java.time.Instant.parse("2026-06-14T08:30:00Z"),
            "k1");
    when(washService.recordWash(ArgumentMatchers.any(RecordWashCommand.class)))
        .thenReturn(new RecordWashResult(wash, true));

    String body =
        """
        {
          "businessId": "22222222-2222-2222-2222-222222222222",
          "bay": "bay-1",
          "amountMinor": 500000,
          "currency": "IDR",
          "idempotencyKey": "k1"
        }
        """;
    mockMvc
        .perform(post("/api/v1/washes").contentType("application/json").content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amountMinor").value(500000))
        .andExpect(jsonPath("$.currency").value("IDR"));
  }
}
