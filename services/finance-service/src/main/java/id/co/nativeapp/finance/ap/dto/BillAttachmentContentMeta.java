package id.co.nativeapp.finance.ap.dto;

/** What the serve needs from the metadata row before it touches the object store. */
public record BillAttachmentContentMeta(
    String contentType, String sha256, String objectKey, String originalFilename) {

  /** The strong ETag value for this content ({@code "<sha256>"}). */
  public String etag() {
    return '"' + (sha256 == null ? "" : sha256.strip()) + '"';
  }

  /**
   * An ASCII-only, quote-free filename for a {@code Content-Disposition} header — anything else is
   * dropped, and a name that empties out falls back to the sha prefix plus the type's extension.
   */
  public String asciiFilename() {
    String base = originalFilename == null ? "" : originalFilename;
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < base.length(); i++) {
      char c = base.charAt(i);
      if (c >= 0x20 && c < 0x7f && c != '"' && c != '\\' && c != ';') {
        out.append(c);
      }
    }
    String name = out.toString().strip();
    if (name.isEmpty()) {
      String ext = "application/pdf".equals(contentType) ? "pdf" : "img";
      name = (sha256 == null ? "attachment" : sha256.strip().substring(0, 12)) + "." + ext;
    }
    return name;
  }
}
