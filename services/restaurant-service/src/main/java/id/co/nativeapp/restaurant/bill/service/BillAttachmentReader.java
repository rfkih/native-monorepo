package id.co.nativeapp.restaurant.bill.service;

import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.restaurant.bill.domain.BillAttachment;
import id.co.nativeapp.restaurant.bill.domain.BillNotFoundException;
import id.co.nativeapp.restaurant.bill.dto.BillAttachmentContentResponse;
import id.co.nativeapp.restaurant.bill.dto.BillAttachmentMetaResponse;
import id.co.nativeapp.restaurant.bill.repository.BillAttachmentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for bill attachments (ADR 0063): the metadata LIST (a narrow projection, no bytes) and
 * a single attachment's CONTENT streamed from the object store. Both RLS-scoped to the bound
 * tenant.
 *
 * <p>Private-by-design: unlike menu images (served anonymously via {@code /api/media}), a bill's
 * receipt is business-sensitive, so the ONLY way to read its bytes is this authenticated,
 * bill-scoped path — the object's MinIO prefix is NOT covered by the anonymous GET policy (V40 /
 * minio init).
 */
@Component
public class BillAttachmentReader {

  private final BillAttachmentRepository attachmentRepository;
  private final MediaStorage mediaStorage;

  public BillAttachmentReader(
      BillAttachmentRepository attachmentRepository, MediaStorage mediaStorage) {
    this.attachmentRepository = attachmentRepository;
    this.mediaStorage = mediaStorage;
  }

  /**
   * A bill's attachments (metadata only), oldest first. RLS already scopes to the tenant, so an
   * unknown/other-tenant bill simply yields an empty list (no extra existence round-trip).
   */
  @Transactional(readOnly = true)
  public List<BillAttachmentMetaResponse> list(UUID billId) {
    return attachmentRepository.findMetaByBillId(billId).stream()
        .map(
            v ->
                new BillAttachmentMetaResponse(
                    v.getId(),
                    v.getContentType(),
                    v.getByteSize(),
                    v.getSha256() == null ? null : v.getSha256().strip(),
                    v.getOriginalFilename(),
                    v.getCreatedAt()))
        .toList();
  }

  /**
   * One attachment's servable bytes. The row must exist in the tenant (RLS) AND belong to {@code
   * billId} (so {@code /bills/A/attachments/X} can never serve an attachment of bill B).
   *
   * @throws BillNotFoundException if the attachment is unknown in this tenant / not on {@code
   *     billId}
   */
  @Transactional(readOnly = true)
  public BillAttachmentContentResponse content(UUID billId, UUID attachmentId) {
    BillAttachment attachment =
        attachmentRepository
            .findById(attachmentId)
            .filter(a -> a.getBillId().equals(billId))
            .orElseThrow(() -> new BillNotFoundException(attachmentId));
    // A store outage fails the serve loudly (500 + error reference) — no silent fallback.
    MediaStorage.StoredObject stored = mediaStorage.get(attachment.getObjectKey());
    return new BillAttachmentContentResponse(
        attachment.getContentType(), stored.data(), attachment.getSha256());
  }
}
