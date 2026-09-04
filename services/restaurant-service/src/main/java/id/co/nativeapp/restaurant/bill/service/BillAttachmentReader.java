package id.co.nativeapp.restaurant.bill.service;

import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.restaurant.bill.domain.BillAttachment;
import id.co.nativeapp.restaurant.bill.domain.BillNotFoundException;
import id.co.nativeapp.restaurant.bill.dto.BillAttachmentContentMeta;
import id.co.nativeapp.restaurant.bill.dto.BillAttachmentMetaResponse;
import id.co.nativeapp.restaurant.bill.repository.BillAttachmentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for bill attachments (ADR 0063): the metadata LIST (a narrow projection, no bytes) and
 * a single attachment's CONTENT streamed from the object store. All DB reads RLS-scoped to the
 * bound tenant.
 *
 * <p>Private-by-design: unlike menu images (served anonymously via {@code /api/media}), a bill's
 * receipt is business-sensitive, so the ONLY way to read its bytes is this authenticated,
 * bill-scoped path — the object's MinIO prefix is NOT covered by the anonymous GET policy (V40 /
 * minio init).
 *
 * <p><strong>Serve is TWO proxied calls, deliberately (flaw-audit C1).</strong> {@link
 * #contentMeta} is the transactional DB read; {@link #payload} fetches the bytes from MinIO with NO
 * transaction — so a slow/hung object store never pins a Hikari connection. The controller composes
 * them (meta → conditional 304 → payload); they are NOT merged into one method because a
 * self-invocation would bypass the proxy and drop both {@code @Transactional} and the RLS GUC
 * aspect (the read would fail closed).
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
   * One attachment's serve METADATA (type, sha256/ETag, object key) — the short transactional read
   * that authorizes the serve. The row must exist in the tenant (RLS) AND belong to {@code billId}
   * (so {@code /bills/A/attachments/X} can never serve an attachment of bill B). The caller checks
   * {@code If-None-Match} against {@link BillAttachmentContentMeta#sha256()} BEFORE calling {@link
   * #payload} — a revalidation costs one metadata row, never a MinIO round-trip.
   *
   * @throws BillNotFoundException if the attachment is unknown in this tenant / not on {@code
   *     billId} (the id in the message is the requested BILL — the resource root of the URL)
   */
  @Transactional(readOnly = true)
  public BillAttachmentContentMeta contentMeta(UUID billId, UUID attachmentId) {
    BillAttachment attachment =
        attachmentRepository
            .findById(attachmentId)
            .filter(a -> a.getBillId().equals(billId))
            .orElseThrow(() -> new BillNotFoundException(billId));
    return new BillAttachmentContentMeta(
        attachment.getContentType(), attachment.getSha256(), attachment.getObjectKey());
  }

  /**
   * The attachment payload from the object store — NO transaction on purpose: the MinIO round-trip
   * (bounded by the client's owned timeouts) must never hold a DB connection. A store outage fails
   * the serve loudly (500 + error reference) — no silent fallback.
   */
  public byte[] payload(String objectKey) {
    return mediaStorage.get(objectKey).data();
  }
}
