package id.co.nativeapp.restaurant.bill.service;

import id.co.nativeapp.mediastorage.MediaKeys;
import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.mediastorage.MediaStorageProperties;
import id.co.nativeapp.mediastorage.Sha256;
import id.co.nativeapp.restaurant.bill.domain.Bill;
import id.co.nativeapp.restaurant.bill.domain.BillAttachment;
import id.co.nativeapp.restaurant.bill.domain.BillAttachmentLimitExceededException;
import id.co.nativeapp.restaurant.bill.domain.BillNotFoundException;
import id.co.nativeapp.restaurant.bill.dto.BillAttachmentMetaResponse;
import id.co.nativeapp.restaurant.bill.repository.BillAttachmentRepository;
import id.co.nativeapp.restaurant.bill.repository.BillRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;
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

  /**
   * Ceiling on attachments per bill (flaw-audit W1). Bounds tenant storage growth AND the serve
   * fan-out (the client eagerly loads every image thumbnail — the cap keeps one bill view to a
   * bounded number of parallel content fetches).
   */
  public static final int MAX_ATTACHMENTS_PER_BILL = 10;

  private final BillRepository billRepository;
  private final BillAttachmentRepository attachmentRepository;
  private final MediaStorage mediaStorage;
  private final MediaStorageProperties mediaProperties;
  private final OutletAccessGuard outletAccessGuard;

  public BillAttachmentWriter(
      BillRepository billRepository,
      BillAttachmentRepository attachmentRepository,
      MediaStorage mediaStorage,
      MediaStorageProperties mediaProperties,
      OutletAccessGuard outletAccessGuard) {
    this.billRepository = billRepository;
    this.attachmentRepository = attachmentRepository;
    this.mediaStorage = mediaStorage;
    this.mediaProperties = mediaProperties;
    this.outletAccessGuard = outletAccessGuard;
  }

  /**
   * Uploads one attachment (photo/PDF) onto a bill in the bound tenant. Returns the metadata DTO,
   * not the entity — the controller must never touch a JPA entity (ArchUnit {@code
   * controllersMustNotDependOnEntities}).
   *
   * @throws MaxUploadSizeExceededException if the bytes exceed {@link BillAttachment#MAX_BYTES}
   *     (413)
   * @throws id.co.nativeapp.restaurant.bill.domain.InvalidBillAttachmentException if the bytes are
   *     an unsupported type or contradict the declared type (422)
   * @throws BillNotFoundException if the bill is unknown in this tenant (404)
   * @throws BillAttachmentLimitExceededException if the bill already carries {@link
   *     #MAX_ATTACHMENTS_PER_BILL} attachments (422)
   */
  @Transactional
  public BillAttachmentMetaResponse upload(
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
    Bill bill =
        billRepository.findById(billId).orElseThrow(() -> new BillNotFoundException(billId));
    // Outlet scoping (flaw-audit W2): attachment WRITES follow the same policy as every bill
    // mutation (BillWriter open/append/pay) — a cashier may only attach to bills at an outlet they
    // are assigned to (owner/manager bypass, grandfathered tenants allow).
    outletAccessGuard.enforce(bill.getBusinessId());

    // Idempotency probe (flaw-audit W3): a byte-identical re-upload onto the SAME bill (network
    // retry, double-tap) returns the existing row — no duplicate row, no second put. Checked
    // BEFORE the cap so a retry can never trip the limit.
    String sha256 = Sha256.hex(data);
    Optional<BillAttachment> existing =
        attachmentRepository.findFirstByBillIdAndSha256(billId, sha256);
    if (existing.isPresent()) {
      return BillAttachmentMetaResponse.from(existing.get());
    }

    // Per-bill ceiling (flaw-audit W1): bounds tenant storage growth and the serve fan-out.
    if (attachmentRepository.countByBillId(billId) >= MAX_ATTACHMENTS_PER_BILL) {
      throw new BillAttachmentLimitExceededException(billId, MAX_ATTACHMENTS_PER_BILL);
    }

    // Payload to the object store (ADR 0048), metadata row to Postgres — put BEFORE the row so a
    // committed row can never dangle; the reverse (stored object, rolled-back row) is a harmless
    // content-addressed orphan. Attachments are never deleted from the store (a byte-identical one
    // on a sibling bill shares the key).
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
    return BillAttachmentMetaResponse.from(attachmentRepository.saveAndFlush(attachment));
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
            // The id in the 404 is the requested BILL — the resource root of the URL (review S4).
            .orElseThrow(() -> new BillNotFoundException(billId));
    // Outlet scoping (flaw-audit W2) — deleting a receipt is a bill mutation like any other.
    Bill bill =
        billRepository.findById(billId).orElseThrow(() -> new BillNotFoundException(billId));
    outletAccessGuard.enforce(bill.getBusinessId());
    attachmentRepository.delete(attachment);
  }

  /**
   * Strip any path component, drop control characters, and cap length WITHOUT splitting a surrogate
   * pair — the multipart filename is client-supplied (review S3: a UTF-16-unit substring could
   * leave a lone surrogate, and control chars would become a header-injection hazard if this value
   * is ever echoed into a {@code Content-Disposition}).
   */
  private static String sanitizeFilename(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String base = name.replace('\\', '/');
    base = base.substring(base.lastIndexOf('/') + 1).strip();
    base = base.replaceAll("\\p{Cntrl}", "");
    if (base.isBlank()) {
      return null;
    }
    if (base.length() > 255) {
      int end = Character.isHighSurrogate(base.charAt(254)) ? 254 : 255;
      base = base.substring(0, end);
    }
    return base;
  }
}
