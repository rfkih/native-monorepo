package id.co.nativeapp.finance.ap.service;

import id.co.nativeapp.finance.ap.domain.BillAttachment;
import id.co.nativeapp.finance.ap.domain.BillNotFoundException;
import id.co.nativeapp.finance.ap.dto.BillAttachmentContentMeta;
import id.co.nativeapp.finance.ap.dto.BillAttachmentMetaResponse;
import id.co.nativeapp.finance.ap.repository.BillAttachmentRepository;
import id.co.nativeapp.mediastorage.MediaStorage;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads a bill's attachments (ADR 0084): metadata in a transaction, the payload outside one. */
@Component
public class BillAttachmentReader {

  private final BillAttachmentRepository attachmentRepository;
  private final MediaStorage mediaStorage;

  public BillAttachmentReader(
      BillAttachmentRepository attachmentRepository, MediaStorage mediaStorage) {
    this.attachmentRepository = attachmentRepository;
    this.mediaStorage = mediaStorage;
  }

  /** A bill's attachments (metadata only), oldest first; an unknown bill yields an empty list. */
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
   * One attachment's serve METADATA — the short transactional read that authorizes the serve. The
   * row must exist in the tenant (RLS) AND belong to {@code billId}.
   *
   * @throws BillNotFoundException if the attachment is unknown in this tenant / not on the bill
   */
  @Transactional(readOnly = true)
  public BillAttachmentContentMeta contentMeta(UUID billId, UUID attachmentId) {
    BillAttachment attachment =
        attachmentRepository
            .findById(attachmentId)
            .filter(a -> a.getBillId().equals(billId))
            .orElseThrow(() -> new BillNotFoundException(billId));
    return new BillAttachmentContentMeta(
        attachment.getContentType(),
        attachment.getSha256(),
        attachment.getObjectKey(),
        attachment.getOriginalFilename());
  }

  /**
   * The payload from the object store — NO transaction on purpose: the store round-trip must never
   * hold a DB connection. A store outage fails the serve loudly (500 + error reference).
   */
  public byte[] payload(String objectKey) {
    return mediaStorage.get(objectKey).data();
  }
}
