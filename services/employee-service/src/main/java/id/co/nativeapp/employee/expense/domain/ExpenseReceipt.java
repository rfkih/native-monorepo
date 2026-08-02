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
 * Phase E3). Stored as {@code bytea} in the employee-service database: no object store in v1, so
 * RLS/Auditable/backups/tenant deletion come free (see V11's Javadoc).
 *
 * <p><strong>Trust boundary.</strong> By the time this constructor runs, {@link
 * ReceiptContentTypeValidator} has already confirmed the DECLARED {@code contentType} matches the
 * ACTUAL magic bytes of {@code data} — the declared header alone is never trusted. {@code sha256}
 * is a lowercase hex digest of the exact stored bytes, computed by the writer.
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

  @Column(name = "data", nullable = false)
  private byte[] data;

  protected ExpenseReceipt() {
    // for JPA
  }

  /**
   * Creates a new receipt row.
   *
   * @param claimId the owning claim; must be non-null
   * @param contentType the verified content type (one of {@link ReceiptContentTypeValidator}'s
   *     whitelisted types); must be non-blank
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
   * @return a defensive copy of the receipt bytes.
   */
  public byte[] getData() {
    return data.clone();
  }

  @Override
  public String toString() {
    return "ExpenseReceipt[id=" + id + ", claimId=" + claimId + ", byteSize=" + byteSize + "]";
  }
}
