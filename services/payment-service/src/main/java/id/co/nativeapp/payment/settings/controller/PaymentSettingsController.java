package id.co.nativeapp.payment.settings.controller;

import id.co.nativeapp.payment.settings.dto.EffectiveSettingsResponse;
import id.co.nativeapp.payment.settings.dto.PaymentSettingsResponse;
import id.co.nativeapp.payment.settings.dto.QrImageContentResponse;
import id.co.nativeapp.payment.settings.dto.QrImageMetaResponse;
import id.co.nativeapp.payment.settings.dto.UpsertSettingsRequest;
import id.co.nativeapp.payment.settings.service.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The payment-settings surface (ADR 0045). Route-level authorization lives at the gateway: {@code
 * /api/v1/payment-settings/**} is owner-only EXCEPT the two POS reads ({@code /effective} and
 * {@code /static-qr/image}), which ride their own POS_ROLES routes; the service layer re-checks the
 * owner guard on every admin operation (defense in depth).
 */
@RestController
@RequestMapping("/api/v1/payment-settings")
public class PaymentSettingsController {

  private final SettingsService service;

  public PaymentSettingsController(SettingsService service) {
    this.service = service;
  }

  @Operation(summary = "List the company's QRIS configuration (owner)")
  @GetMapping
  public PaymentSettingsResponse list() {
    return service.list();
  }

  @Operation(summary = "Upsert the company-default QRIS settings (owner)")
  @PutMapping
  public PaymentSettingsResponse upsertCompanyDefault(@RequestBody UpsertSettingsRequest request) {
    return service.upsertCompanyDefault(request);
  }

  @Operation(summary = "Upsert an outlet's QRIS mode override (owner; mode only)")
  @PutMapping("/outlets/{outletId}")
  public PaymentSettingsResponse upsertOutletOverride(
      @PathVariable UUID outletId, @RequestBody UpsertSettingsRequest request) {
    return service.upsertOutletOverride(outletId, request);
  }

  @Operation(summary = "Delete an outlet's QRIS override (owner)")
  @DeleteMapping("/outlets/{outletId}")
  public ResponseEntity<Void> deleteOutletOverride(@PathVariable UUID outletId) {
    service.deleteOutletOverride(outletId);
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Upload/replace the company-level static QRIS image (owner)")
  @PostMapping(path = "/static-qr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public QrImageMetaResponse uploadCompanyStaticQr(@RequestPart("file") MultipartFile file) {
    return service.uploadStaticQr(null, file.getContentType(), bytesOf(file));
  }

  @Operation(summary = "Upload/replace an outlet's static QRIS image (owner)")
  @PostMapping(
      path = "/outlets/{outletId}/static-qr",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public QrImageMetaResponse uploadOutletStaticQr(
      @PathVariable UUID outletId, @RequestPart("file") MultipartFile file) {
    return service.uploadStaticQr(outletId, file.getContentType(), bytesOf(file));
  }

  @Operation(summary = "Remove the company-level static QRIS image (owner)")
  @DeleteMapping("/static-qr")
  public ResponseEntity<Void> removeCompanyStaticQr() {
    service.removeStaticQr(null);
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Remove an outlet's static QRIS image (owner)")
  @DeleteMapping("/outlets/{outletId}/static-qr")
  public ResponseEntity<Void> removeOutletStaticQr(@PathVariable UUID outletId) {
    service.removeStaticQr(outletId);
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Effective QRIS mode/availability for the till's outlet (POS roles)")
  @GetMapping("/effective")
  public EffectiveSettingsResponse effective(
      @RequestParam(name = "businessId", required = false) UUID businessId) {
    return service.effective(businessId);
  }

  @Operation(summary = "Effective static QRIS image blob for the till's outlet (POS roles)")
  @GetMapping("/static-qr/image")
  public ResponseEntity<byte[]> effectiveImage(
      @RequestParam(name = "businessId", required = false) UUID businessId) {
    QrImageContentResponse image = service.effectiveImage(businessId);
    return ResponseEntity.ok()
        // Private: an authenticated, tenant-scoped blob — never shared-cacheable. The sha256 ETag
        // lets the till revalidate cheaply after the 5-minute freshness window.
        .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
        .eTag('"' + image.sha256() + '"')
        // nosniff (security review N3): the magic-byte check validates only the LEADING bytes, so
        // a stored polyglot must never be content-sniffed into something executable — the browser
        // renders exactly the canonical image type or nothing.
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(image.contentType()))
        .body(image.data());
  }

  private static byte[] bytesOf(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the uploaded QRIS image part", e);
    }
  }
}
