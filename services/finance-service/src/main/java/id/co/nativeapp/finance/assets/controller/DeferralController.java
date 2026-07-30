package id.co.nativeapp.finance.assets.controller;

import id.co.nativeapp.finance.assets.domain.DeferralKind;
import id.co.nativeapp.finance.assets.dto.CreateDeferralRequest;
import id.co.nativeapp.finance.assets.dto.DeferralResponse;
import id.co.nativeapp.finance.assets.service.CreateDeferralResult;
import id.co.nativeapp.finance.assets.service.DeferralReader;
import id.co.nativeapp.finance.assets.service.DeferralWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/deferrals} — prepaid expenses + deferred revenue for the bound tenant (Phase 6,
 * ADR 0020): create (posts the opening entry) and the list with remaining balances. The monthly
 * amortization run ({@code POST /api/v1/assets/runs}) posts each month's share. DTOs only; faults
 * via {@link AssetAdvice} (the same advice package).
 */
@Tag(name = "Deferrals", description = "Prepaid expenses + deferred revenue for the bound tenant.")
@RestController
@RequestMapping("/api/v1/deferrals")
@Validated
public class DeferralController {

  private final DeferralWriter deferralWriter;
  private final DeferralReader deferralReader;

  public DeferralController(DeferralWriter deferralWriter, DeferralReader deferralReader) {
    this.deferralWriter = deferralWriter;
    this.deferralReader = deferralReader;
  }

  @Operation(summary = "Create a deferral (posts the opening prepaid / deferred-revenue entry)")
  @PostMapping
  public ResponseEntity<List<DeferralResponse>> create(
      @Valid @RequestBody CreateDeferralRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    // REQUIRED (code-review C-1): creating a deferral posts money; a keyless retry double-posts.
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException(
          "the Idempotency-Key header is required to create a deferral");
    }
    CreateDeferralResult result =
        deferralWriter.create(
            DeferralKind.valueOf(request.kind()),
            request.description(),
            request.totalMinor(),
            request.months(),
            request.startPeriod(),
            request.currency(),
            idempotencyKey);
    // The list is small and the fresh row is in it; return it (the console re-renders the table).
    // A replay of a prior attempt → 200 (nothing re-posted); a fresh create → 201 + Location.
    if (!result.created()) {
      return ResponseEntity.ok(deferralReader.list());
    }
    return ResponseEntity.created(URI.create("/api/v1/deferrals/" + result.deferralId()))
        .body(deferralReader.list());
  }

  @Operation(summary = "The deferral list (total, amortized to date, remaining)")
  @GetMapping
  public List<DeferralResponse> list() {
    return deferralReader.list();
  }
}
