package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.assets.controller.AssetAdvice;
import id.co.nativeapp.finance.assets.controller.DeferralController;
import id.co.nativeapp.finance.assets.dto.DeferralResponse;
import id.co.nativeapp.finance.assets.service.CreateDeferralResult;
import id.co.nativeapp.finance.assets.service.DeferralReader;
import id.co.nativeapp.finance.assets.service.DeferralWriter;
import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.security.ApiExceptionHandler;
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
 * Web-slice test for {@link DeferralController}: create (201), list (200), and validation 400s.
 * Services mocked.
 */
@WebMvcTest(DeferralController.class)
@Import({AssetAdvice.class, ApiExceptionHandler.class, ConstraintViolationAdvice.class})
class DeferralControllerTest {

  private static final UUID DEFERRAL = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private DeferralWriter deferralWriter;
  @MockitoBean private DeferralReader deferralReader;

  private static DeferralResponse sample() {
    return new DeferralResponse(
        DEFERRAL,
        "PREPAID_EXPENSE",
        "A year's rent",
        6_000_000L,
        6,
        "2026-08",
        "IDR",
        1_000_000L,
        5_000_000L);
  }

  @Test
  void createReturns201WithTheList() throws Exception {
    when(deferralWriter.create(any(), any(), anyLong(), anyInt(), any(), any(), any()))
        .thenReturn(new CreateDeferralResult(DEFERRAL, true));
    when(deferralReader.list()).thenReturn(List.of(sample()));

    String body =
        "{\"kind\":\"PREPAID_EXPENSE\",\"description\":\"A year's rent\",\"totalMinor\":6000000,"
            + "\"months\":6,\"startPeriod\":\"2026-08\",\"currency\":\"IDR\"}";
    mockMvc
        .perform(
            post("/api/v1/deferrals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "deferral-1")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$[0].remainingMinor").value(5_000_000L));
  }

  @Test
  void createReplayReturns200() throws Exception {
    when(deferralWriter.create(any(), any(), anyLong(), anyInt(), any(), any(), any()))
        .thenReturn(new CreateDeferralResult(DEFERRAL, false));
    when(deferralReader.list()).thenReturn(List.of(sample()));

    String body =
        "{\"kind\":\"PREPAID_EXPENSE\",\"description\":\"A year's rent\",\"totalMinor\":6000000,"
            + "\"months\":6,\"startPeriod\":\"2026-08\",\"currency\":\"IDR\"}";
    mockMvc
        .perform(
            post("/api/v1/deferrals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "deferral-1")
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void createWithoutAnIdempotencyKeyIsA400() throws Exception {
    String body =
        "{\"kind\":\"PREPAID_EXPENSE\",\"description\":\"A year's rent\",\"totalMinor\":6000000,"
            + "\"months\":6,\"startPeriod\":\"2026-08\",\"currency\":\"IDR\"}";
    mockMvc
        .perform(post("/api/v1/deferrals").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/asset-invalid-request"));
  }

  @Test
  void createWithABadKindIsA400() throws Exception {
    String body =
        "{\"kind\":\"BOGUS\",\"description\":\"x\",\"totalMinor\":1,"
            + "\"months\":1,\"startPeriod\":\"2026-08\",\"currency\":\"IDR\"}";
    mockMvc
        .perform(post("/api/v1/deferrals").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createWithABadStartPeriodIsA400() throws Exception {
    String body =
        "{\"kind\":\"DEFERRED_REVENUE\",\"description\":\"x\",\"totalMinor\":1,"
            + "\"months\":1,\"startPeriod\":\"08-2026\",\"currency\":\"IDR\"}";
    mockMvc
        .perform(post("/api/v1/deferrals").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void listReturns200() throws Exception {
    when(deferralReader.list()).thenReturn(List.of(sample()));
    mockMvc
        .perform(get("/api/v1/deferrals"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].kind").value("PREPAID_EXPENSE"));
  }
}
