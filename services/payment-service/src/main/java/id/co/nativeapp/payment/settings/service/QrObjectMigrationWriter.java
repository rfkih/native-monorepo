package id.co.nativeapp.payment.settings.service;

import id.co.nativeapp.mediastorage.MediaKeys;
import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.mediastorage.MediaStorageProperties;
import id.co.nativeapp.payment.settings.domain.PaymentSettings;
import id.co.nativeapp.payment.settings.repository.PaymentSettingsRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * READ-THROUGH migration of a legacy inline-bytea static QRIS image to the object store (ADR 0048)
 * — the employee-service {@code ReceiptObjectMigrationWriter} idiom: each time a legacy row's image
 * is actually served, its bytes are copied to the store and the row flips to object-backed, inside
 * a normal tenant-bound transaction (RLS scopes everything).
 *
 * <p>Opportunistic and NEVER load-bearing: failures are logged and swallowed — the serve path
 * already holds the bytes, and the row stays a valid legacy row for the next attempt. Concurrent
 * serves race benignly (content-addressed key; the loser finds the row already migrated). Gate:
 * {@code native.media.read-through-migrate} (default on).
 */
@Component
public class QrObjectMigrationWriter {

  private static final Logger log = LoggerFactory.getLogger(QrObjectMigrationWriter.class);

  private final PaymentSettingsRepository repository;
  private final MediaStorage mediaStorage;
  private final MediaStorageProperties mediaProperties;
  private final boolean enabled;

  public QrObjectMigrationWriter(
      PaymentSettingsRepository repository,
      MediaStorage mediaStorage,
      MediaStorageProperties mediaProperties,
      @Value("${native.media.read-through-migrate:true}") boolean enabled) {
    this.repository = repository;
    this.mediaStorage = mediaStorage;
    this.mediaProperties = mediaProperties;
    this.enabled = enabled;
  }

  /** Copies the row's inline image to the object store and flips it, if still legacy. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void migrateOpportunistically(UUID settingsId) {
    if (!enabled) {
      return;
    }
    try {
      String tenant = TenantContext.require().companyId();
      PaymentSettings row = repository.findById(settingsId).orElse(null);
      if (row == null || row.getStaticQrObjectKey() != null || !row.hasStaticQr()) {
        return; // gone, already object-backed, or image removed meanwhile
      }
      byte[] data = row.getStaticQrData();
      String objectKey =
          MediaKeys.imageKey(
              mediaProperties.servicePrefix(),
              tenant,
              SettingsWriter.MEDIA_DOMAIN,
              row.getStaticQrSha256(),
              row.getStaticQrContentType());
      mediaStorage.put(objectKey, data, row.getStaticQrContentType());
      row.moveStaticQrToObjectStore(objectKey);
      repository.saveAndFlush(row);
    } catch (RuntimeException e) {
      // Row id only — never payload. The row remains a valid legacy row for the next serve.
      log.warn("read-through QRIS migration skipped for {}: {}", settingsId, e.getMessage());
    }
  }
}
