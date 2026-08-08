package id.co.nativeapp.employee.expense.service;

import id.co.nativeapp.employee.expense.domain.ExpenseReceipt;
import id.co.nativeapp.employee.expense.repository.ExpenseReceiptRepository;
import id.co.nativeapp.mediastorage.MediaKeys;
import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.mediastorage.MediaStorageProperties;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * READ-THROUGH migration of legacy inline-bytea receipts to the object store (ADR 0048): each time
 * a legacy row is actually served, its bytes are copied to the store and the row flips to
 * object-backed — the corpus converges to fully-migrated with ZERO operational work, inside normal
 * tenant-bound transactions (RLS scopes everything; no Flyway backfill, no tenant enumeration).
 *
 * <p>Opportunistic and NEVER load-bearing: any failure here is logged and swallowed — the serve
 * path already holds the bytes it is about to return, and the row stays a perfectly valid legacy
 * row for the next attempt. Concurrent serves of the same receipt race benignly: the object key is
 * content-addressed (both put identical bytes to the identical key) and the loser of the row update
 * simply finds the row already migrated.
 *
 * <p>{@code REQUIRES_NEW}: the serve path runs read-only; the flip needs its own short write
 * transaction. Gate: {@code native.media.read-through-migrate} (default on; off = legacy rows are
 * served from bytea indefinitely, still correct).
 */
@Component
public class ReceiptObjectMigrationWriter {

  private static final Logger log = LoggerFactory.getLogger(ReceiptObjectMigrationWriter.class);

  private final ExpenseReceiptRepository receiptRepository;
  private final MediaStorage mediaStorage;
  private final MediaStorageProperties mediaProperties;
  private final boolean enabled;

  public ReceiptObjectMigrationWriter(
      ExpenseReceiptRepository receiptRepository,
      MediaStorage mediaStorage,
      MediaStorageProperties mediaProperties,
      @Value("${native.media.read-through-migrate:true}") boolean enabled) {
    this.receiptRepository = receiptRepository;
    this.mediaStorage = mediaStorage;
    this.mediaProperties = mediaProperties;
    this.enabled = enabled;
  }

  /**
   * Copies the receipt's inline bytes to the object store and flips the row, if it is still a
   * legacy row. Failures are swallowed (see class Javadoc). Call AFTER the serve path has the bytes
   * in hand — never on the critical path.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void migrateOpportunistically(UUID receiptId) {
    if (!enabled) {
      return;
    }
    try {
      String tenant = TenantContext.require().companyId();
      ExpenseReceipt receipt = receiptRepository.findById(receiptId).orElse(null);
      byte[] data = receipt == null ? null : receipt.getData();
      if (data == null) {
        return; // gone, or already object-backed (a concurrent serve won the race)
      }
      String objectKey =
          MediaKeys.imageKey(
              mediaProperties.servicePrefix(),
              tenant,
              ReceiptWriter.MEDIA_DOMAIN,
              receipt.getSha256(),
              receipt.getContentType());
      mediaStorage.put(objectKey, data, receipt.getContentType());
      receipt.moveToObjectStore(objectKey);
      receiptRepository.saveAndFlush(receipt);
    } catch (RuntimeException e) {
      // Row id only — never payload. The row remains a valid legacy row for the next serve.
      log.warn("read-through receipt migration skipped for {}: {}", receiptId, e.getMessage());
    }
  }
}
