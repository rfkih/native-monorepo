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
import id.co.nativeapp.finance.assets.controller.AssetController;
import id.co.nativeapp.finance.assets.domain.AssetAlreadyDisposedException;
import id.co.nativeapp.finance.assets.domain.AssetDepreciationBehindException;
import id.co.nativeapp.finance.assets.domain.AssetNotFoundException;
import id.co.nativeapp.finance.assets.domain.IdempotencyKeyConflictException;
import id.co.nativeapp.finance.assets.dto.AssetDetailResponse;
import id.co.nativeapp.finance.assets.dto.AssetResponse;
import id.co.nativeapp.finance.assets.dto.RunResponse;
import id.co.nativeapp.finance.assets.service.AcquireAssetResult;
import id.co.nativeapp.finance.assets.service.AmortizationRunWriter;
import id.co.nativeapp.finance.assets.service.AssetDisposalWriter;
import id.co.nativeapp.finance.assets.service.AssetReader;
import id.co.nativeapp.finance.assets.service.DisposeAssetResult;
import id.co.nativeapp.finance.assets.service.FixedAssetWriter;
import id.co.nativeapp.finance.assets.service.RunAmortizationResult;
import id.co.nativeapp.finance.assets.service.RunReader;
import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
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
 * Web-slice test for {@link AssetController}: acquire (201), the register (200), the run (201 fresh
 * / 200 idempotent re-run), and the fault mappings from {@link AssetAdvice} — 400 (bad bounds), 404
 * (unknown asset), 422 (currency). Services mocked. Mirrors {@code TaxControllerTest}.
 */
@WebMvcTest(AssetController.class)
@Import({AssetAdvice.class, ApiExceptionHandler.class, ConstraintViolationAdvice.class})
class AssetControllerTest {

  private static final UUID ASSET = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
  private static final UUID RUN = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private FixedAssetWriter fixedAssetWriter;
  @MockitoBean private AssetDisposalWriter assetDisposalWriter;
  @MockitoBean private AmortizationRunWriter amortizationRunWriter;
  @MockitoBean private AssetReader assetReader;
  @MockitoBean private RunReader runReader;

  private static final String ACQUIRE_BODY =
      "{\"name\":\"Espresso machine\",\"acquisitionDate\":\"2026-07-15\",\"costMinor\":12000000,"
          + "\"salvageMinor\":0,\"usefulLifeMonths\":12,\"currency\":\"IDR\"}";

  private static final String DISPOSE_BODY =
      "{\"disposalDate\":\"2026-07-20\",\"proceedsMinor\":12000000,\"currency\":\"IDR\"}";

  private static AssetDetailResponse sampleDetail() {
    return new AssetDetailResponse(
        new AssetResponse(
            ASSET,
            "Espresso machine",
            LocalDate.parse("2026-07-15"),
            "2026-08",
            12_000_000L,
            0L,
            12,
            "IDR",
            "ACTIVE",
            null,
            null,
            1_000_000L,
            11_000_000L),
        List.of(new AssetDetailResponse.ScheduleLine("2026-08", 1, 1_000_000L, "IDR")));
  }

  private static RunResponse sampleRun() {
    return new RunResponse(RUN, "2026-08", 3, 3_000_000L, Instant.parse("2026-08-31T00:00:00Z"));
  }

  @Test
  void acquireReturns201WithDetail() throws Exception {
    when(fixedAssetWriter.acquire(any(), any(), anyLong(), anyLong(), anyInt(), any(), any()))
        .thenReturn(new AcquireAssetResult(ASSET, true));
    when(assetReader.detail(ASSET)).thenReturn(sampleDetail());

    mockMvc
        .perform(
            post("/api/v1/assets")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "acquire-1")
                .content(ACQUIRE_BODY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.asset.name").value("Espresso machine"))
        .andExpect(jsonPath("$.asset.bookValueMinor").value(11_000_000L))
        .andExpect(jsonPath("$.asset.startPeriod").value("2026-08"));
  }

  @Test
  void acquireReplayReturns200WithTheOriginal() throws Exception {
    // A retried acquire with the same key replays the original asset (created == false) → 200.
    when(fixedAssetWriter.acquire(any(), any(), anyLong(), anyLong(), anyInt(), any(), any()))
        .thenReturn(new AcquireAssetResult(ASSET, false));
    when(assetReader.detail(ASSET)).thenReturn(sampleDetail());

    mockMvc
        .perform(
            post("/api/v1/assets")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "acquire-1")
                .content(ACQUIRE_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asset.id").value(ASSET.toString()));
  }

  @Test
  void acquireWithoutAnIdempotencyKeyIsA400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/assets").contentType(MediaType.APPLICATION_JSON).content(ACQUIRE_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/asset-invalid-request"));
  }

  @Test
  void disposeReturns201WithDetail() throws Exception {
    when(assetDisposalWriter.dispose(any(), any(), anyLong(), any(), any()))
        .thenReturn(new DisposeAssetResult(ASSET, true));
    when(assetReader.detail(ASSET)).thenReturn(sampleDetail());

    mockMvc
        .perform(
            post("/api/v1/assets/" + ASSET + "/dispose")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dispose-1")
                .content(DISPOSE_BODY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.asset.id").value(ASSET.toString()));
  }

  @Test
  void disposeReplayReturns200WithTheOriginal() throws Exception {
    // A retried dispose with the same key replays the original disposal (created == false) → 200.
    when(assetDisposalWriter.dispose(any(), any(), anyLong(), any(), any()))
        .thenReturn(new DisposeAssetResult(ASSET, false));
    when(assetReader.detail(ASSET)).thenReturn(sampleDetail());

    mockMvc
        .perform(
            post("/api/v1/assets/" + ASSET + "/dispose")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dispose-1")
                .content(DISPOSE_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asset.id").value(ASSET.toString()));
  }

  @Test
  void disposeWithoutAnIdempotencyKeyIsA400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/assets/" + ASSET + "/dispose")
                .contentType(MediaType.APPLICATION_JSON)
                .content(DISPOSE_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/asset-invalid-request"));
  }

  @Test
  void disposeOfAnUnknownAssetIsA404() throws Exception {
    when(assetDisposalWriter.dispose(any(), any(), anyLong(), any(), any()))
        .thenThrow(new AssetNotFoundException(ASSET));

    mockMvc
        .perform(
            post("/api/v1/assets/" + ASSET + "/dispose")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dispose-404")
                .content(DISPOSE_BODY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/asset-not-found"));
  }

  @Test
  void disposeOfAnAlreadyDisposedAssetIsA409() throws Exception {
    when(assetDisposalWriter.dispose(any(), any(), anyLong(), any(), any()))
        .thenThrow(new AssetAlreadyDisposedException(ASSET));

    mockMvc
        .perform(
            post("/api/v1/assets/" + ASSET + "/dispose")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dispose-2")
                .content(DISPOSE_BODY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/asset-already-disposed"));
  }

  @Test
  void disposeWithDepreciationOutOfStepIsA409() throws Exception {
    when(assetDisposalWriter.dispose(any(), any(), anyLong(), any(), any()))
        .thenThrow(new AssetDepreciationBehindException("2 of 3 due months posted"));

    mockMvc
        .perform(
            post("/api/v1/assets/" + ASSET + "/dispose")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dispose-3")
                .content(DISPOSE_BODY))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/asset-depreciation-behind"));
  }

  @Test
  void disposeReusingAnotherAssetsKeyIsA409() throws Exception {
    // The path id is authoritative (code-review W1): a key that disposed a DIFFERENT asset must
    // not silently replay it as this asset's disposal.
    when(assetDisposalWriter.dispose(any(), any(), anyLong(), any(), any()))
        .thenThrow(new IdempotencyKeyConflictException(ASSET, RUN));

    mockMvc
        .perform(
            post("/api/v1/assets/" + ASSET + "/dispose")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dispose-reused")
                .content(DISPOSE_BODY))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/asset-idempotency-key-conflict"));
  }

  @Test
  void disposeWithNegativeProceedsIsA400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/assets/" + ASSET + "/dispose")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dispose-4")
                .content(
                    "{\"disposalDate\":\"2026-07-20\",\"proceedsMinor\":-1,\"currency\":\"IDR\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void acquireWithSalvageAboveCostIsA400() throws Exception {
    when(fixedAssetWriter.acquire(any(), any(), anyLong(), anyLong(), anyInt(), any(), any()))
        .thenThrow(new IllegalArgumentException("salvage must be non-negative and less than cost"));

    mockMvc
        .perform(
            post("/api/v1/assets")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "acquire-2")
                .content(ACQUIRE_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/asset-invalid-request"));
  }

  @Test
  void acquireWithADivergentCurrencyIsA422() throws Exception {
    when(fixedAssetWriter.acquire(any(), any(), anyLong(), anyLong(), anyInt(), any(), any()))
        .thenThrow(new MismatchedPostingCurrencyException("2026-07", "IDR", "USD"));

    mockMvc
        .perform(
            post("/api/v1/assets")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "acquire-3")
                .content(ACQUIRE_BODY))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/asset-currency-mismatch"));
  }

  @Test
  void registerReturns200() throws Exception {
    when(assetReader.register()).thenReturn(List.of(sampleDetail().asset()));
    mockMvc
        .perform(get("/api/v1/assets"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].accumulatedMinor").value(1_000_000L));
  }

  @Test
  void unknownAssetIsA404() throws Exception {
    when(assetReader.detail(any())).thenThrow(new AssetNotFoundException(ASSET));
    mockMvc
        .perform(get("/api/v1/assets/{id}", ASSET))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/asset-not-found"));
  }

  @Test
  void runReturns201WhenFresh() throws Exception {
    when(amortizationRunWriter.run("2026-08")).thenReturn(new RunAmortizationResult(RUN, true));
    when(runReader.detail(RUN)).thenReturn(sampleRun());

    mockMvc
        .perform(
            post("/api/v1/assets/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"2026-08\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.period").value("2026-08"))
        .andExpect(jsonPath("$.lineCount").value(3));
  }

  @Test
  void rerunReturns200WithTheExistingRun() throws Exception {
    when(amortizationRunWriter.run("2026-08")).thenReturn(new RunAmortizationResult(RUN, false));
    when(runReader.detail(RUN)).thenReturn(sampleRun());

    mockMvc
        .perform(
            post("/api/v1/assets/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"2026-08\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(RUN.toString()));
  }

  @Test
  void runWithABadPeriodIsA400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/assets/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"2026-13\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void runsHistoryReturns200() throws Exception {
    when(runReader.history()).thenReturn(List.of(sampleRun()));
    mockMvc
        .perform(get("/api/v1/assets/runs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].totalMinor").value(3_000_000L));
  }

  @Test
  void acquireWithZeroCostIsRejectedByValidation() throws Exception {
    String body =
        "{\"name\":\"X\",\"acquisitionDate\":\"2026-07-15\",\"costMinor\":0,"
            + "\"salvageMinor\":0,\"usefulLifeMonths\":12,\"currency\":\"IDR\"}";
    mockMvc
        .perform(
            post("/api/v1/assets")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "acquire-4")
                .content(body))
        .andExpect(status().isBadRequest());
  }
}
