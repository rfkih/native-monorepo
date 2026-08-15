package id.co.nativeapp.restaurant.bill.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code bill_attachment} aggregate — one uploaded photo/PDF for a bill: the "real receipt" or
 * a supporting document (ADR 0063). This METADATA row lives here (Auditable, RLS, sha256); the
 * PAYLOAD lives in the object store under {@code objectKey} (ADR 0048, content-addressed). A bill
 * may have MANY attachments (unlike an expense claim's single replaceable receipt).
 *
 * <p><strong>Trust boundary.</strong> By construction time {@code AttachmentContentValidator} has
 * confirmed the declared multipart {@code Content-Type} matches the ACTUAL bytes
 * (jpeg/png/webp/pdf) — the declared header alone is never trusted; {@code sha256} is the lowercase
 * hex digest of the exact stored bytes.
 *
 * <p><strong>Money/PII.</strong> Not applicable — a receipt/document photo carries neither; stored
 * unencrypted, never logged. Extends {@link Auditable} (rule 4), under the {@code bill_attachment}
 * RLS policy (rule 5, V40).
 */
@Entity
@Table(name = "bill_attachment")
public class BillAttachment extends Auditable {

  /** Server-enforced size cap (5 MiB) — mirrors the V40 {@code CHECK} + the multipart property. */
  public static final int MAX_BYTES = 5 * 1024 * 1024;

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "bill_id", nullable = false, updatable = false)
  private UUID billId;

  @Column(name = "content_type", nullable = false, length = 64)
  private String contentType;

  @Column(name = "byte_size", nullable = false)
  private int byteSize;

  // CHAR(64) (bpchar) — a sha256 hex digest is always exactly 64 chars (matches the V40 column so
  // Hibernate schema validation does not flag a bpchar/varchar mismatch; same idiom as
  // ExpenseReceipt).
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "sha256", nullable = false, length = 64)
  private String sha256;

  @Column(name = "object_key", nullable = false)
  private String objectKey;

  /** The uploader's original filename (sanitized), for display/download; nullable. */
  @Column(name = "original_filename", length = 255)
  private String originalFilename;

  protected BillAttachment() {
    // for JPA
  }

  /**
   * Creates an object-backed attachment row: the payload already lives in the object store under
   * {@code objectKey}; this row carries metadata only.
   *
   * @throws IllegalArgumentException if {@code byteSize} is out of bounds or a required field blank
   */
  public BillAttachment(
      UUID billId,
      String contentType,
      int byteSize,
      String sha256,
      String objectKey,
      String originalFilename) {
    this.id = UUID.randomUUID();
    this.billId = Objects.requireNonNull(billId, "billId");
    this.contentType = requireNonBlank(contentType, "contentType");
    if (byteSize <= 0 || byteSize > MAX_BYTES) {
      throw new IllegalArgumentException(
          "bill attachment byteSize must be between 1 and " + MAX_BYTES + ", was " + byteSize);
    }
    this.byteSize = byteSize;
    this.sha256 = requireNonBlank(sha256, "sha256");
    this.objectKey = requireNonBlank(objectKey, "objectKey");
    this.originalFilename = originalFilename; // nullable, already sanitized by the writer
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBillId() {
    return billId;
  }

  public String getContentType() {
    return contentType;
  }

  public int getByteSize() {
    return byteSize;
  }

  public String getSha256() {
    return sha256.strip();
  }

  public String getObjectKey() {
    return objectKey;
  }

  public String getOriginalFilename() {
    return originalFilename;
  }
}
