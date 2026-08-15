package id.co.nativeapp.restaurant.bill.service;

import id.co.nativeapp.mediastorage.MediaKeys;
import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.mediastorage.MediaStorageProperties;
import id.co.nativeapp.mediastorage.Sha256;
import id.co.nativeapp.restaurant.bill.domain.BillAttachment;
import id.co.nativeapp.restaurant.bill.domain.BillNotFoundException;
import id.co.nativeapp.restaurant.bill.repository.BillAttachmentRepository;
import id.co.nativeapp.restaurant.bill.repository.BillRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * The {@code @Transactional} write unit for bill attachments (ADR 0063). A distinct {@code *Writer}
 * bean so the method runs through the Spring proxy and {@link RlsAutoApplyAspect} sets the tenant
 * GUC (rule 5). Mirrors employee-service's {@code ReceiptWriter} (the fleet's media-upload
 * template, ADR 0048), differing in two ways: a bill accepts MANY attachments (append, never
 * replace), and it accepts a PDF as well as an image ({@link AttachmentContentValidator}).
 *
 * <p><strong>Order of operations.</strong> Fail fast on the bytes (size, then magic-byte type)
 * BEFORE any DB work; verify the bill exists in the bound tenant (RLS-scoped {@code findById});
 * then put the validated bytes to the object store INSIDE the transaction (a rollback orphans one
 * harmless content-addressed object) and insert the metadata row. The stored object is
 * content-addressed, so the same photo attached to two bills shares one key.
 */
@Component
public class BillAttachmentWriter {

  /** The media family segment in this service's object keys ({@code restaurant/{co}/bill/…}). */
  static final String MEDIA_DOMAIN = "bill";

  private final BillRepository billRepository;
  private final BillAttachmentRepository attachmentRepository;
  private final MediaStorage mediaStorage;
  private final MediaStorageProperties mediaProperties;

  public BillAttachmentWriter(
      BillRepository billRepository,
      BillAttachmentRepository attachmentRepository,
      MediaStorage mediaStorage,
      MediaStorageProperties mediaProperties) {
    this.billRepository = billRepository;
    this.attachmentRepository = attachmentRepository;
    this.mediaStorage = mediaStorage;
    this.mediaProperties = mediaProperties;
  }

  /**
   * Uploads one attachment (photo/PDF) onto a bill in the bound tenant.
   *
   * @throws MaxUploadSizeExceededException if the bytes exceed {@link BillAttachment#MAX_BYTES}
   *     (413)
   * @throws id.co.nativeapp.restaurant.bill.domain.InvalidBillAttachmentException if the bytes are
   *     an unsupported type or contradict the declared type (422)
   * @throws BillNotFoundException if the bill is unknown in this tenant (404)
   */
  @Transactional
  public BillAttachment upload(
      UUID billId, String declaredContentType, byte[] data, String originalFilename) {
    String tenant = TenantContext.require().companyId();

    Objects.requireNonNull(data, "data");
    if (data.length > BillAttachment.MAX_BYTES) {
      throw new MaxUploadSizeExceededException(BillAttachment.MAX_BYTES);
    }
    // The CANONICAL detected type is what gets stored + served — never the untrusted declared
    // header. An EMPTY part is deliberately NOT treated as "too large": it falls through here and
    // the magic-byte validator rejects it as an unsupported type (422), matching ReceiptWriter.
    String canonicalType = AttachmentContentValidator.validate(declaredContentType, data);

    // The bill must exist in the bound tenant (RLS scopes findById). Verified AFTER the cheap byte
    // checks so garbage input never even touches the DB.
    billRepository.findById(billId).orElseThrow(() -> new BillNotFoundException(billId));

    // Payload to the object store (ADR 0048), metadata row to Postgres — put BEFORE the row so a
    // committed row can never dangle; the reverse (stored object, rolled-back row) is a harmless
    // content-addressed orphan. Attachments are never deleted from the store (a byte-identical one
    // on a sibling bill shares the key).
    String sha256 = Sha256.hex(data);
    String objectKey =
        MediaKeys.imageKey(
            mediaProperties.servicePrefix(), tenant, MEDIA_DOMAIN, sha256, canonicalType);
    mediaStorage.put(objectKey, data, canonicalType);

    BillAttachment attachment =
        new BillAttachment(
            billId,
            canonicalType,
            data.length,
            sha256,
            objectKey,
            sanitizeFilename(originalFilename));
    attachment.setCompanyId(tenant);
    return attachmentRepository.saveAndFlush(attachment);
  }

  /**
   * Removes an attachment's metadata row (RLS-scoped, and the row must belong to {@code billId}).
   * The content-addressed object is deliberately NOT deleted (ADR 0048): a byte-identical
   * attachment on a sibling bill would still reference it, so a per-row store delete could destroy
   * live data.
   *
   * @throws BillNotFoundException if the attachment is unknown in this tenant / not on {@code
   *     billId}
   */
  @Transactional
  public void delete(UUID billId, UUID attachmentId) {
    BillAttachment attachment =
        attachmentRepository
            .findById(attachmentId)
            .filter(a -> a.getBillId().equals(billId))
            .orElseThrow(() -> new BillNotFoundException(attachmentId));
    attachmentRepository.delete(attachment);
  }

  /** Strip any path component and cap length — the multipart filename is client-supplied. */
  private static String sanitizeFilename(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String base = name.replace('\\', '/');
    base = base.substring(base.lastIndexOf('/') + 1).strip();
    if (base.isBlank()) {
      return null;
    }
    return base.length() > 255 ? base.substring(0, 255) : base;
  }
}
