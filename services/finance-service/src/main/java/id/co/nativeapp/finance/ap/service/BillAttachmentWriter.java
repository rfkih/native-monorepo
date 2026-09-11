package id.co.nativeapp.finance.ap.service;

import id.co.nativeapp.finance.ap.domain.Bill;
import id.co.nativeapp.finance.ap.domain.BillAttachment;
import id.co.nativeapp.finance.ap.domain.BillAttachmentLimitExceededException;
import id.co.nativeapp.finance.ap.domain.BillNotFoundException;
import id.co.nativeapp.finance.ap.domain.BillStateException;
import id.co.nativeapp.finance.ap.domain.BillStatus;
import id.co.nativeapp.finance.ap.dto.BillAttachmentMetaResponse;
import id.co.nativeapp.finance.ap.repository.BillAttachmentRepository;
import id.co.nativeapp.finance.ap.repository.BillRepository;
import id.co.nativeapp.mediastorage.MediaKeys;
import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.mediastorage.MediaStorageProperties;
import id.co.nativeapp.mediastorage.Sha256;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Attaches the vendor's invoice (photo/PDF) to an AP bill (ADR 0084) — the evidence an audit asks
 * for. The payload goes to the object store under a content-addressed key (ADR 0048), the metadata
 * row to Postgres, in that order, so a committed row can never dangle. ADR 0063's shape, on
 * finance's own bill aggregate.
 */
@Component
public class BillAttachmentWriter {

  /** The media family segment in this service's object keys ({@code finance/{co}/bill/…}). */
  static final String MEDIA_DOMAIN = "bill";

  /** Ceiling on attachments per bill — bounds tenant storage growth and the serve fan-out. */
  public static final int MAX_ATTACHMENTS_PER_BILL = 10;

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
   * Uploads one attachment onto a bill in the bound tenant. Returns the metadata DTO, never the
   * entity.
   *
   * @throws MaxUploadSizeExceededException if the bytes exceed {@link BillAttachment#MAX_BYTES}
   *     (413)
   * @throws id.co.nativeapp.finance.ap.domain.InvalidBillAttachmentException if the bytes are an
   *     unsupported type or contradict the declared type (422)
   * @throws BillNotFoundException if the bill is unknown in this tenant (404)
   * @throws BillStateException if the bill is VOID (409) — a voided bill takes no more evidence
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
    // The CANONICAL detected type is what gets stored + served — never the untrusted header. An
    // EMPTY part falls through to the validator and is rejected as an unsupported type (422).
    String canonicalType = AttachmentContentValidator.validate(declaredContentType, data);
    Bill bill =
        billRepository.findById(billId).orElseThrow(() -> new BillNotFoundException(billId));
    if (bill.getStatus() == BillStatus.VOID) {
      throw new BillStateException("a VOID bill takes no attachments");
    }
    // Idempotency probe: a byte-identical re-upload (network retry, double-tap) returns the
    // existing row — no duplicate row, no second put. Checked BEFORE the cap.
    String sha256 = Sha256.hex(data);
    Optional<BillAttachment> existing =
        attachmentRepository.findFirstByBillIdAndSha256(billId, sha256);
    if (existing.isPresent()) {
      return BillAttachmentMetaResponse.from(existing.get());
    }
    if (attachmentRepository.countByBillId(billId) >= MAX_ATTACHMENTS_PER_BILL) {
      throw new BillAttachmentLimitExceededException(billId, MAX_ATTACHMENTS_PER_BILL);
    }
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
   * attachment on a sibling bill would still reference it.
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
            .orElseThrow(() -> new BillNotFoundException(billId));
    attachmentRepository.delete(attachment);
  }

  /**
   * Strip any path component, drop control characters, and cap length WITHOUT splitting a surrogate
   * pair — the multipart filename is client-supplied.
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
