package id.co.nativeapp.employee.config;

/**
 * Raised when a PII value fails to encrypt or decrypt. Its message NEVER contains the plaintext,
 * the key, or the ciphertext (rule 6 — PII is never logged); it names only the failed operation. A
 * decryption failure here also signals a tampered/corrupted column (GCM authentication-tag
 * mismatch), which is surfaced loudly rather than returning a wrong value.
 */
public class PiiEncryptionException extends RuntimeException {

  public PiiEncryptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
