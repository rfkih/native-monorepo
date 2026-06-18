package id.co.nativeapp.carwash.wash.controller;

import id.co.nativeapp.carwash.wash.dto.RecordWashCommand;
import id.co.nativeapp.carwash.wash.dto.RecordWashResult;
import id.co.nativeapp.carwash.wash.dto.WashRequest;
import id.co.nativeapp.carwash.wash.dto.WashResponse;
import id.co.nativeapp.carwash.wash.service.WashService;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/washes} — record a wash.
 *
 * <p>The request carries only the business payload; the tenant ({@code company_id}) and actor come
 * from the bound {@link id.co.nativeapp.tenant.TenantContext TenantContext} (set at the request
 * edge by the gateway/JWT, or {@link id.co.nativeapp.carwash.config.DevTenantFilter
 * DevTenantFilter} in dev) — never from the body (rule 5).
 *
 * <p>The resource lives under {@code /api/v1} (URI versioning — ENGINEERING-STANDARDS §1.1), so the
 * gateway, which routes {@code /api/v1/washes/**} preserving the full path, reaches this handler.
 *
 * <p><strong>Gated by the carwash entitlement.</strong> {@link WashService} rejects the write with
 * {@code 403} (via {@link id.co.nativeapp.carwash.config.NotEntitledAdvice NotEntitledAdvice}) when
 * the company is not entitled to the {@code carwash} module — no wash row, no event.
 *
 * <p>Returns {@code 201 Created} (with a {@code Location} header pointing at the new resource) when
 * a new wash was recorded (and exactly one {@code SaleRecorded} + the declared {@code
 * MetricPublished} set emitted), and {@code 200 OK} when a retry with the same {@code
 * idempotency_key} returned the pre-existing wash (no second event, no {@code Location}).
 */
@RestController
@RequestMapping("/api/v1/washes")
public class WashController {

  private final WashService washService;

  public WashController(WashService washService) {
    this.washService = washService;
  }

  @PostMapping
  public ResponseEntity<WashResponse> recordWash(@Valid @RequestBody WashRequest request) {
    RecordWashCommand command =
        new RecordWashCommand(
            request.businessId(),
            request.bay(),
            request.amountMinor(),
            request.currency(),
            request.upsellName(),
            request.upsellAmountMinor(),
            request.occurredAt() != null ? request.occurredAt() : Instant.now(),
            request.idempotencyKey());

    RecordWashResult result = washService.recordWash(command);
    WashResponse body = result.wash();
    return result.created()
        ? ResponseEntity.created(URI.create("/api/v1/washes/" + body.id())).body(body)
        : ResponseEntity.ok(body);
  }
}
