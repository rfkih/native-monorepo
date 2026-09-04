package id.co.nativeapp.restaurant.bill.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for the bill-attachment LIST (ADR 0063) — metadata only, never the payload bytes
 * (those stream from the object store on demand). Backs the native query on {@code
 * BillAttachmentRepository}; RLS scopes it to the session tenant (rule 5).
 *
 * <p>Lives in its own {@code projection} package — a read model, not the write-side entity nor a
 * DTO.
 */
public interface BillAttachmentView {

  UUID getId();

  String getContentType();

  long getByteSize();

  String getSha256();

  String getOriginalFilename();

  Instant getCreatedAt();
}
