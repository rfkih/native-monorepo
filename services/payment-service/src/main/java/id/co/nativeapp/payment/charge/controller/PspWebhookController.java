package id.co.nativeapp.payment.charge.controller;

import id.co.nativeapp.payment.charge.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The inbound Midtrans notification endpoint (ADR 0045) — the fleet's second fully-anonymous
 * surface: NO JWT (declared in {@code native.security.public-paths}); the gateway forwards it
 * through its own anonymous route (per-IP rate limit + tenant-header strip), and {@link
 * WebhookService} does ALL authentication (provisional tenant bind → RLS-scoped loads →
 * constant-time signature verify). Once authenticated the answer is 200 even for parked anomalies —
 * Midtrans retries non-2xx, and a parked case is a human's to resolve, not the PSP's to resend.
 */
@RestController
@RequestMapping("/api/v1/psp-webhooks")
public class PspWebhookController {

  private final WebhookService service;

  public PspWebhookController(WebhookService service) {
    this.service = service;
  }

  @Operation(summary = "Midtrans settlement notification (anonymous; signature-verified inside)")
  @PostMapping("/midtrans/{companyId}")
  public Map<String, String> midtrans(
      @PathVariable UUID companyId, @RequestBody(required = false) String rawBody) {
    service.handleMidtrans(companyId, rawBody);
    return Map.of("status", "ok");
  }
}
