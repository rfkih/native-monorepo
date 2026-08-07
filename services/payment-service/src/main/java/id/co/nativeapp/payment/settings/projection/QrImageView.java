package id.co.nativeapp.payment.settings.projection;

/**
 * Read model for serving the static QRIS image blob (CODE-STRUCTURE §3.3): exactly the three
 * columns the authenticated image endpoint needs — content type (canonical, magic-byte-verified at
 * upload), the sha256 (the ETag), and the bytes. Never the credential columns.
 */
public interface QrImageView {

  String getContentType();

  String getSha256();

  byte[] getData();
}
