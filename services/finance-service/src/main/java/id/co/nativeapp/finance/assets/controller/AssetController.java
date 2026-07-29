package id.co.nativeapp.finance.assets.controller;

import id.co.nativeapp.finance.assets.dto.AcquireAssetRequest;
import id.co.nativeapp.finance.assets.dto.AssetDetailResponse;
import id.co.nativeapp.finance.assets.dto.AssetResponse;
import id.co.nativeapp.finance.assets.dto.RunAmortizationRequest;
import id.co.nativeapp.finance.assets.dto.RunResponse;
import id.co.nativeapp.finance.assets.service.AcquireAssetResult;
import id.co.nativeapp.finance.assets.service.AmortizationRunWriter;
import id.co.nativeapp.finance.assets.service.AssetReader;
import id.co.nativeapp.finance.assets.service.FixedAssetWriter;
import id.co.nativeapp.finance.assets.service.RunAmortizationResult;
import id.co.nativeapp.finance.assets.service.RunReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/assets} — the fixed-asset register + the monthly amortization run for the bound
 * tenant (Phase 6, ADR 0020): acquire (posts capex), the register with book values, an asset's
 * detail + schedule history, and running depreciation/amortization for a period (idempotent — a
 * re-run returns 200 with the existing run, posting nothing). Every response is a DTO; typed faults
 * map to RFC-7807 via {@link AssetAdvice}.
 *
 * <p><strong>ILLUSTRATIVE / SME-gated:</strong> the straight-line-only method, the full-month
 * start-after-acquisition convention, and the COA codes — see ADR 0020 + V34/V35 headers.
 */
@Tag(name = "Fixed Assets", description = "Asset register + monthly depreciation/amortization run.")
@RestController
@RequestMapping("/api/v1/assets")
@Validated
public class AssetController {

  private final FixedAssetWriter fixedAssetWriter;
  private final AmortizationRunWriter amortizationRunWriter;
  private final AssetReader assetReader;
  private final RunReader runReader;

  public AssetController(
      FixedAssetWriter fixedAssetWriter,
      AmortizationRunWriter amortizationRunWriter,
      AssetReader assetReader,
      RunReader runReader) {
    this.fixedAssetWriter = fixedAssetWriter;
    this.amortizationRunWriter = amortizationRunWriter;
    this.assetReader = assetReader;
    this.runReader = runReader;
  }

  @Operation(summary = "Acquire (capitalize) a fixed asset — posts Dr asset cost / Cr cash-clearing")
  @PostMapping
  public ResponseEntity<AssetDetailResponse> acquire(
      @Valid @RequestBody AcquireAssetRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    // The key is REQUIRED (code-review C-1, the AR/AP payment rationale): acquiring posts money, so
    // a keyless request — which could silently double-post capex on a retry — is rejected with 400.
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException(
          "the Idempotency-Key header is required to acquire an asset");
    }
    AcquireAssetResult result =
        fixedAssetWriter.acquire(
            request.name(),
            request.acquisitionDate(),
            request.costMinor(),
            request.salvageMinor(),
            request.usefulLifeMonths(),
            request.currency(),
            idempotencyKey);
    AssetDetailResponse detail = assetReader.detail(result.assetId());
    // Idempotent-POST contract: a fresh acquire → 201 + Location; a replay → 200 with the original.
    if (!result.created()) {
      return ResponseEntity.ok(detail);
    }
    return ResponseEntity.created(URI.create("/api/v1/assets/" + result.assetId())).body(detail);
  }

  @Operation(summary = "The asset register (cost, accumulated depreciation, book value)")
  @GetMapping
  public List<AssetResponse> register() {
    return assetReader.register();
  }

  @Operation(summary = "The amortization-run history (most-recent period first)")
  @GetMapping("/runs")
  public List<RunResponse> runs() {
    return runReader.history();
  }

  @Operation(
      summary =
          "Run depreciation/amortization for a period (idempotent — a re-run returns 200, posting"
              + " nothing)")
  @PostMapping("/runs")
  public ResponseEntity<RunResponse> run(@Valid @RequestBody RunAmortizationRequest request) {
    RunAmortizationResult result = amortizationRunWriter.run(request.period());
    RunResponse detail = runReader.detail(result.runId());
    // Idempotent-POST contract: a fresh run → 201 + Location; a re-run → 200 with the existing run.
    if (!result.created()) {
      return ResponseEntity.ok(detail);
    }
    return ResponseEntity.created(URI.create("/api/v1/assets/runs/" + detail.id())).body(detail);
  }

  @Operation(summary = "One asset's detail + its posted schedule history")
  @GetMapping("/{id}")
  public AssetDetailResponse get(@PathVariable UUID id) {
    return assetReader.detail(id);
  }
}
