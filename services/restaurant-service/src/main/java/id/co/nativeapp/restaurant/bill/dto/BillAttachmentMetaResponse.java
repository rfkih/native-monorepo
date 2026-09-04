package id.co.nativeapp.restaurant.bill.dto;

import id.co.nativeapp.restaurant.bill.domain.BillAttachment;
import java.time.Instant;
import java.util.UUID;

/**
 * One bill attachment's metadata (ADR 0063) — id, type, size, digest, original filename, upload
 * time. The bytes are NOT here; the client streams them from {@code GET
 * /api/v1/bills/{id}/attachments/{attachmentId}}. No money/PII travels.
 */
public record BillAttachmentMetaResponse(
    UUID id,
    String contentType,
    long byteSize,
    String sha256,
    String originalFilename,
    Instant uploadedAt) {

  public static BillAttachmentMetaResponse from(BillAttachment a) {
    return new BillAttachmentMetaResponse(
        a.getId(),
        a.getContentType(),
        a.getByteSize(),
        a.getSha256(),
        a.getOriginalFilename(),
        a.getCreatedAt());
  }
}
