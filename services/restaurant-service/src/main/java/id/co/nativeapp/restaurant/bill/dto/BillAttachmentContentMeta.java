package id.co.nativeapp.restaurant.bill.dto;

/**
 * The serve metadata for one bill attachment (ADR 0063): the canonical content type, the sha256
 * (served as the ETag — checked against {@code If-None-Match} BEFORE any object-store fetch), and
 * the object key the payload lives under. Internal carrier from the reader's transactional metadata
 * read to the controller — the BYTES are deliberately not here (they are fetched outside any DB
 * transaction; flaw-audit C1). Not a JSON body.
 */
public record BillAttachmentContentMeta(String contentType, String sha256, String objectKey) {

  /** The strong ETag value for this content ({@code "<sha256>"}). */
  public String etag() {
    return '"' + (sha256 == null ? "" : sha256.strip()) + '"';
  }
}
