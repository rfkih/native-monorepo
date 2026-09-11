package id.co.nativeapp.finance.ap.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for a bill attachment's metadata ({@code GET /api/v1/ap/bills/{id}/attachments},
 * ADR 0084) — never the payload. Snake_case native aliases map to these accessors.
 */
public interface BillAttachmentView {

  UUID getId();

  String getContentType();

  long getByteSize();

  String getSha256();

  String getOriginalFilename();

  Instant getCreatedAt();
}
