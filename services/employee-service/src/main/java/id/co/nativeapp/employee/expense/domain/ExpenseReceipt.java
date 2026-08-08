package id.co.nativeapp.employee.expense.domain;

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
 * The {@code expense_receipt} aggregate — one uploaded receipt photo for a claim (ADR 0030 §8,
 * Phase E3). The METADATA row lives here (Auditable, RLS, sha256 — unchanged); since ADR 0048 the
 * PAYLOAD lives in the object store under {@code objectKey} for new uploads, while legacy rows keep
 * their {@code bytea} until the read-through migration converts them. The V15 {@code CHECK}
 * guarantees exactly this dual-home invariant: a row always has {@code data} OR {@code objectKey}
 * (receipts are expense evidence — a payload-less metadata row must be unrepresentable).
 *
 * <p><strong>Trust boundary.</strong> By the time a constructor runs, the shared magic-byte
 * validator (libs/media-storage {@code ImageContentTypeValidator}) has already confirmed the
 * DECLARED {@code contentType} matches the ACTUAL bytes — the declared header alone is never
 * trusted. {@code sha256} is a lowercase hex digest of the exact stored bytes, computed by the
 * writer.
 *
 * <p><strong>Money/PII (rule 6/8).</strong> Not applicable — a receipt photo carries neither. It is
 * a business document (ADR 0030 §8), stored unencrypted, never logged.
 *
 * <p>Extends {@link Auditable} (rule 4); under the {@code expense_receipt} RLS policy (rule 5,
 * V11).
 */
@Entity
@Table(name = "expense_receipt")
public class ExpenseReceipt extends Auditable {

  /**
   * The server-enforced size cap (5 MiB) — mirrors the DB {@code CHECK} constraint and the {@code
   * spring.servlet.multipart.max-file-size} property (defense in depth: {@link
   * id.co.nativeapp.employee.expense.service.ReceiptWriter} re-checks this even though Spring's
   * multipart resolver already caps the part it accepts).
   */
  public static final int MAX_BYTES = 5 * 1024 * 1024;

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "claim_id", nullable = false, updatable = false)
  private UUID claimId;

  @Column(name = "content_type", nullable = false, length = 64)
  private String contentType;

  @Column(name = "byte_size", nullable = false)
  private int byteSize;

  // CHAR(64) (bpchar), not VARCHAR — a sha256 hex digest is always exactly 64 chars, so this
  // mirrors MoneyEmbeddable#currency's CHAR(n) idiom (@JdbcTypeCode(SqlTypes.CHAR), matching the
  // V11 CHAR(64) column so Hibernate's schema VALIDATION does not flag a bpchar/varchar mismatch).
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "sha256", nullable = false, length = 64)
  private String sha256;

  /** Inline payload — LEGACY rows only (pre-ADR-0048); {@code null} once object-backed. */
  @Column(name = "data")
  private byte[] data;

  /**
   * Object-store key of the payload (ADR 0048): {@code
   * employee/{companyId}/receipt/{sha256}.{ext}}; {@code null} on a legacy inline row. Exactly one
   * of {@code data}/{@code objectKey} is set (V15 CHECK).
   */
  @Column(name = "object_key")
  private String objectKey;

  protected ExpenseReceipt() {
    // for JPA
  }

  /**
   * Creates a LEGACY inline-payload receipt row (pre-ADR-0048 shape — retained for tests and for
   * representing not-yet-migrated data; the production writer creates object-backed rows).
   *
   * @param claimId the owning claim; must be non-null
   * @param contentType the verified canonical content type; must be non-blank
   * @param data the receipt bytes; must be non-empty and no larger than {@link #MAX_BYTES}
   * @param sha256 the lowercase hex SHA-256 digest of {@code data}; must be non-blank
   * @throws IllegalArgumentException if {@code data} is empty or exceeds {@link #MAX_BYTES}
   */
  public ExpenseReceipt(UUID claimId, String contentType, byte[] data, String sha256) {
    this.id = UUID.randomUUID();
    this.claimId = Objects.requireNonNull(claimId, "claimId");
    this.contentType = requireNonBlank(contentType, "contentType");
    Objects.requireNonNull(data, "data");
    if (data.length == 0 || data.length > MAX_BYTES) {
      throw new IllegalArgumentException(
          "expense receipt data must be between 1 and " + MAX_BYTES + " bytes, was " + data.length);
    }
    this.data = data.clone();
    this.byteSize = data.length;
    this.sha256 = requireNonBlank(sha256, "sha256");
  }

  /**
   * Creates an OBJECT-BACKED receipt row (ADR 0048): the payload already lives in the object store
   * under {@code objectKey}; this row carries the metadata only ({@code data} stays {@code null}).
   *
   * @param claimId the owning claim; must be non-null
   * @param contentType the verified canonical content type; must be non-blank
   * @param byteSize the stored payload's exact size; must be 1..{@link #MAX_BYTES}
   * @param sha256 the lowercase hex SHA-256 digest of the stored bytes; must be non-blank
   * @param objectKey the content-addressed object key; must be non-blank
   * @throws IllegalArgumentException if {@code byteSize} is out of bounds
   */
  public ExpenseReceipt(
      UUID claimId, String contentType, int byteSize, String sha256, String objectKey) {
    this.id = UUID.randomUUID();
    this.claimId = Objects.requireNonNull(claimId, "claimId");
    this.contentType = requireNonBlank(contentType, "contentType");
    if (byteSize <= 0 || byteSize > MAX_BYTES) {
      throw new IllegalArgumentException(
          "expense receipt byteSize must be between 1 and " + MAX_BYTES + ", was " + byteSize);
    }
    this.byteSize = byteSize;
    this.sha256 = requireNonBlank(sha256, "sha256");
    this.objectKey = requireNonBlank(objectKey, "objectKey");
  }

  /**
   * Read-through migration (ADR 0048): the payload has been copied to the object store — drop the
   * inline bytes and record the key. Idempotence lives in the caller (it skips already-migrated
   * rows); this method just enforces the exactly-one-payload-home invariant.
   *
   * @param objectKey the content-addressed key the bytes were stored under; must be non-blank
   * @throws IllegalStateException if this row is already object-backed
   */
  public void moveToObjectStore(String objectKey) {
    if (this.data == null) {
      throw new IllegalStateException("receipt " + id + " is already object-backed");
    }
    this.objectKey = requireNonBlank(objectKey, "objectKey");
    this.data = null;
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

  public UUID getClaimId() {
    return claimId;
  }

  public String getContentType() {
    return contentType;
  }

  public int getByteSize() {
    return byteSize;
  }

  public String getSha256() {
    // CHAR(64)/bpchar never actually pads a 64-char hex digest, but strip() defensively mirrors
    // MoneyEmbeddable#getCurrency's CHAR(n) read idiom in case a future writer ever stores fewer
    // than 64 chars.
    return sha256.strip();
  }

  /**
   * @return a defensive copy of the inline receipt bytes, or {@code null} when this row is
   *     object-backed (the payload lives under {@link #getObjectKey()}).
   */
  public byte[] getData() {
    return data == null ? null : data.clone();
  }

  /** The object-store key of the payload (ADR 0048); {@code null} on a legacy inline row. */
  public String getObjectKey() {
    return objectKey;
  }

  @Override
  public String toString() {
    return "ExpenseReceipt[id=" + id + ", claimId=" + claimId + ", byteSize=" + byteSize + "]";
  }
}
