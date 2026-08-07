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
 * The payment-settings surface (ADR 0045, DIVISION-scope amendment). Route-level authorization
 * lives at the gateway: {@code /api/v1/payment-settings/**} is owner-only EXCEPT the two POS reads
 * ({@code /effective} and {@code /static-qr/image}), which ride their own POS_ROLES routes; the
 * service layer re-checks the owner guard on every admin operation (defense in depth). {@code
 * /units/{unitId}} covers BOTH an outlet and a division override — payment-service holds no org
 * read model, so the caller (the console) is trusted to pass whichever org-unit id the owner
 * picked.
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

  @Operation(summary = "Upsert a unit's (outlet or division) QRIS mode override (owner; mode only)")
  @PutMapping("/units/{unitId}")
  public PaymentSettingsResponse upsertUnitOverride(
      @PathVariable UUID unitId, @RequestBody UpsertSettingsRequest request) {
    return service.upsertUnitOverride(unitId, request);
  }

  @Operation(summary = "Delete a unit's (outlet or division) QRIS override (owner)")
  @DeleteMapping("/units/{unitId}")
  public ResponseEntity<Void> deleteUnitOverride(@PathVariable UUID unitId) {
    service.deleteUnitOverride(unitId);
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Upload/replace the company-level static QRIS image (owner)")
  @PostMapping(path = "/static-qr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public QrImageMetaResponse uploadCompanyStaticQr(@RequestPart("file") MultipartFile file) {
    return service.uploadStaticQr(null, file.getContentType(), bytesOf(file));
  }

  @Operation(summary = "Upload/replace a unit's (outlet or division) static QRIS image (owner)")
  @PostMapping(path = "/units/{unitId}/static-qr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public QrImageMetaResponse uploadUnitStaticQr(
      @PathVariable UUID unitId, @RequestPart("file") MultipartFile file) {
    return service.uploadStaticQr(unitId, file.getContentType(), bytesOf(file));
  }

  @Operation(summary = "Remove the company-level static QRIS image (owner)")
  @DeleteMapping("/static-qr")
  public ResponseEntity<Void> removeCompanyStaticQr() {
    service.removeStaticQr(null);
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Remove a unit's (outlet or division) static QRIS image (owner)")
  @DeleteMapping("/units/{unitId}/static-qr")
  public ResponseEntity<Void> removeUnitStaticQr(@PathVariable UUID unitId) {
    service.removeStaticQr(unitId);
    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "Effective QRIS mode/availability for the till's outlet/division (POS roles)")
  @GetMapping("/effective")
  public EffectiveSettingsResponse effective(
      @RequestParam(name = "businessId", required = false) UUID businessId,
      @RequestParam(name = "divisionId", required = false) UUID divisionId) {
    return service.effective(businessId, divisionId);
  }

  @Operation(
      summary = "Effective static QRIS image blob for the till's outlet/division (POS roles)")
  @GetMapping("/static-qr/image")
  public ResponseEntity<byte[]> effectiveImage(
      @RequestParam(name = "businessId", required = false) UUID businessId,
      @RequestParam(name = "divisionId", required = false) UUID divisionId) {
    QrImageContentResponse image = service.effectiveImage(businessId, divisionId);
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
