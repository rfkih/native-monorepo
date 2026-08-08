package id.co.nativeapp.mediastorage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 → lowercase hex, the content-addressing digest for {@link MediaKeys} and the integrity
 * column every media metadata row stores ({@code sha256 CHAR(64)}, the expense-receipt idiom).
 */
public final class Sha256 {

  private Sha256() {}

  /** Returns the lowercase-hex SHA-256 of {@code data}. */
  public static String hex(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      // Every conforming JRE ships SHA-256; this is unreachable outside a broken runtime.
      throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
    }
  }
}
