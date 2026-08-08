package id.co.nativeapp.payment.settings.service;

import id.co.nativeapp.mediastorage.ImageContentTypeValidator;
import id.co.nativeapp.mediastorage.MediaKeys;
import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.mediastorage.MediaStorageProperties;
import id.co.nativeapp.mediastorage.Sha256;
import id.co.nativeapp.mediastorage.UnsupportedImageTypeException;
import id.co.nativeapp.payment.settings.domain.InvalidQrImageException;
import id.co.nativeapp.payment.settings.domain.PaymentSettings;
import id.co.nativeapp.payment.settings.domain.PaymentSettingsNotFoundException;
import id.co.nativeapp.payment.settings.domain.ProviderEnvironment;
import id.co.nativeapp.payment.settings.domain.PspProvider;
import id.co.nativeapp.payment.settings.domain.QrisMode;
import id.co.nativeapp.payment.settings.domain.SettingsValidationException;
import id.co.nativeapp.payment.settings.dto.UpsertSettingsRequest;
import id.co.nativeapp.payment.settings.repository.PaymentSettingsRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Owns the {@code @Transactional} unit of work for every {@code payment_settings} mutation (ADR
 * 0045, DIVISION-scope amendment). A distinct bean (the {@code *Writer} pattern) so the method is
 * invoked through the Spring proxy and the RLS aspect sets the tenant GUC (rule 5); {@link
 * TenantContext} scopes every row.
 *
 * <p><strong>Upsert semantics.</strong> One row per scope (company default / per-unit override,
 * where a unit is an outlet OR a division id), enforced by the V2/V4 partial-unique indexes; the
 * writer finds-or-creates the scope's row. Replays are naturally idempotent (same payload → same
 * end state), so no {@code Idempotency-Key} is involved — the expense-receipt "replace-idempotent
 * by nature" reasoning.
 *
 * <p><strong>Credentials are company-level.</strong> A unit override may set mode (and carry its
 * own image via the upload path), never gateway fields — one Midtrans account per company (ADR
 * 0045), enforced uniformly whether the unit is an outlet or a division. {@code serverKey}/{@code
 * clientKey} are write-only: absent keeps the stored value. Clearing stored credentials is
 * deliberately NOT exposed in v1 (an owner replaces them instead); a remove-credentials affordance
 * is a recorded residual.
 *
 * <p><strong>Image upload.</strong> Size re-checked against {@link
 * PaymentSettings#MAX_QR_IMAGE_BYTES} (defense in depth behind the multipart cap → the same 413
 * either path), then magic-byte verified BEFORE any row is touched — the fleet-shared
 * libs/media-storage {@link ImageContentTypeValidator} since ADR 0048, its exception translated to
 * this feature's {@link InvalidQrImageException} so the 422 contract is unchanged; the CANONICAL
 * detected type is what gets stored and later served. The bytes themselves go to the OBJECT STORE
 * ({@code payment/{tenant}/qr/{sha256}.{ext}}, put inside the write transaction — a rollback
 * orphans one harmless content-addressed object); the row keeps metadata + key. A replaced or
 * removed image's old object is deleted best-effort strictly AFTER COMMIT. Uploading to a scope
 * with no row yet creates it with mode {@link QrisMode#STATIC} — uploading an image IS the intent
 * to use it.
 */
@Component
public class SettingsWriter {

  private static final Logger log = LoggerFactory.getLogger(SettingsWriter.class);

  /** The media family segment in this service's object keys. */
  static final String MEDIA_DOMAIN = "qr";

  private final PaymentSettingsRepository repository;
  private final MediaStorage mediaStorage;
  private final MediaStorageProperties mediaProperties;

  public SettingsWriter(
      PaymentSettingsRepository repository,
      MediaStorage mediaStorage,
      MediaStorageProperties mediaProperties) {
    this.repository = repository;
    this.mediaStorage = mediaStorage;
    this.mediaProperties = mediaProperties;
  }

  /** Upserts the company default scope (mode + optional gateway credentials). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PaymentSettings upsertCompanyDefault(UpsertSettingsRequest request) {
    TenantContext.require();
    QrisMode mode = QrisMode.parse(request.mode());
    PaymentSettings row = repository.findByOrgUnitIdIsNull().orElseGet(() -> newRow(null, mode));
    row.changeMode(mode);
    if (request.hasGatewayFields()) {
      PspProvider provider = PspProvider.parse(request.provider());
      ProviderEnvironment environment = ProviderEnvironment.parse(request.environment());
      row.applyGatewayCredentials(provider, environment, request.serverKey(), request.clientKey());
    }
    return repository.saveAndFlush(row);
  }

  /**
   * Upserts a unit (outlet or division) override (mode only — gateway fields are rejected, 422).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PaymentSettings upsertUnitOverride(UUID unitId, UpsertSettingsRequest request) {
    TenantContext.require();
    Objects.requireNonNull(unitId, "unitId");
    if (request.hasGatewayFields()) {
      throw new SettingsValidationException(
          "Gateway credentials live on the company default settings, not a unit override.");
    }
    QrisMode mode = QrisMode.parse(request.mode());
    PaymentSettings row = repository.findByOrgUnitId(unitId).orElseGet(() -> newRow(unitId, mode));
    row.changeMode(mode);
    return repository.saveAndFlush(row);
  }

  /** Deletes a unit (outlet or division) override entirely (its image goes with it). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deleteUnitOverride(UUID unitId) {
    TenantContext.require();
    PaymentSettings row =
        repository
            .findByOrgUnitId(unitId)
            .orElseThrow(
                () ->
                    new PaymentSettingsNotFoundException(
                        "No payment-settings override exists for this unit."));
    repository.delete(row);
  }

  /**
   * Attaches (or replaces) the static QRIS image on a scope ({@code unitId == null} = company).
   *
   * @return the row after the swap (its sha256/byte-size feed the upload response)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PaymentSettings attachStaticQr(UUID unitId, String declaredContentType, byte[] data) {
    String tenant = TenantContext.require().companyId();
    Objects.requireNonNull(data, "data");
    if (data.length > PaymentSettings.MAX_QR_IMAGE_BYTES) {
      throw new MaxUploadSizeExceededException(PaymentSettings.MAX_QR_IMAGE_BYTES);
    }
    // The CANONICAL detected type is what gets stored (and later served) — never the raw declared
    // header. Fail fast on the bytes BEFORE touching any row.
    String canonicalContentType;
    try {
      canonicalContentType = ImageContentTypeValidator.validate(declaredContentType, data);
    } catch (UnsupportedImageTypeException e) {
      throw new InvalidQrImageException(e.getDeclaredContentType(), e.getDetectedContentType());
    }
    String sha256 = Sha256.hex(data);

    PaymentSettings row = findScope(unitId).orElseGet(() -> newRow(unitId, QrisMode.STATIC));
    String previousObjectKey = row.getStaticQrObjectKey();

    // Payload to the object store (ADR 0048), metadata + key on the row.
    String objectKey =
        MediaKeys.imageKey(
            mediaProperties.servicePrefix(), tenant, MEDIA_DOMAIN, sha256, canonicalContentType);
    mediaStorage.put(objectKey, data, canonicalContentType);
    row.attachStaticQrObject(canonicalContentType, data.length, sha256, objectKey);
    PaymentSettings saved = repository.saveAndFlush(row);
    scheduleReplacedObjectCleanup(previousObjectKey, objectKey);
    return saved;
  }

  /** Removes a scope's static QRIS image (404 when the scope has none). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void removeStaticQr(UUID unitId) {
    TenantContext.require();
    PaymentSettings row =
        findScope(unitId)
            .filter(PaymentSettings::hasStaticQr)
            .orElseThrow(
                () ->
                    new PaymentSettingsNotFoundException(
                        "This scope has no static QRIS image to remove."));
    String previousObjectKey = row.getStaticQrObjectKey();
    row.removeStaticQr();
    repository.saveAndFlush(row);
    scheduleReplacedObjectCleanup(previousObjectKey, null);
  }

  /**
   * Best-effort delete of a replaced/removed image's object, strictly {@code afterCommit} (the
   * employee-service ReceiptWriter idiom): deleting before commit could lose the object a
   * rolled-back transaction's surviving row still references. A failed delete is an accepted orphan
   * (ADR 0048). No-ops when there is nothing to delete, the key is unchanged (re-upload of the
   * identical image — content addressing), or no transaction is active (plain unit tests).
   */
  private void scheduleReplacedObjectCleanup(String previousObjectKey, String newObjectKey) {
    if (previousObjectKey == null
        || previousObjectKey.equals(newObjectKey)
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              mediaStorage.delete(previousObjectKey);
            } catch (RuntimeException e) {
              log.warn("replaced QRIS object not deleted (accepted orphan): {}", e.getMessage());
            }
          }
        });
  }

  private java.util.Optional<PaymentSettings> findScope(UUID unitId) {
    return unitId == null ? repository.findByOrgUnitIdIsNull() : repository.findByOrgUnitId(unitId);
  }

  private PaymentSettings newRow(UUID unitId, QrisMode mode) {
    PaymentSettings row = new PaymentSettings(unitId, mode);
    row.setCompanyId(TenantContext.require().companyId());
    return row;
  }
}
