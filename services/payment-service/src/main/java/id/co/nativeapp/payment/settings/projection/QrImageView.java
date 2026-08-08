package id.co.nativeapp.payment.settings.projection;

import java.util.UUID;

/**
 * Read model for serving the static QRIS image (CODE-STRUCTURE §3.3): exactly what the
 * authenticated image endpoint needs — content type (canonical, magic-byte-verified at upload), the
 * sha256 (the ETag), the row id (so the read-through migration can flip the row, ADR 0048), and the
 * payload's home: {@code objectKey} on an object-backed row, inline {@code data} on a legacy row
 * (exactly one is non-null). Never the credential columns.
 */
public interface QrImageView {

  UUID getId();

  String getContentType();

  String getSha256();

  /** Inline bytes — LEGACY rows only; {@code null} once object-backed. */
  byte[] getData();

  /** Object-store key (ADR 0048); {@code null} on a legacy inline row. */
  String getObjectKey();
}
