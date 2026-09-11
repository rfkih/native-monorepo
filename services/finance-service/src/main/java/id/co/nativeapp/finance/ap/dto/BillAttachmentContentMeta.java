package id.co.nativeapp.finance.ap.dto;

/** What the serve needs from the metadata row before it touches the object store. */
public record BillAttachmentContentMeta(String contentType, String sha256, String objectKey) {

  /** The strong ETag value for this content ({@code "<sha256>"}). */
  public String etag() {
    return '"' + (sha256 == null ? "" : sha256.strip()) + '"';
  }
}
