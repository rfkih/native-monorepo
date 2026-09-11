package id.co.nativeapp.finance.ap.dto;

import id.co.nativeapp.finance.ap.domain.BillAttachment;
import java.time.Instant;
import java.util.UUID;

/** One attachment's metadata ({@code GET /api/v1/ap/bills/{id}/attachments}, ADR 0084). */
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
