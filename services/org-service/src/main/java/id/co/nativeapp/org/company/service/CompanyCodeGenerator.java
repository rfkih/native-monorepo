package id.co.nativeapp.org.company.service;

import java.security.SecureRandom;

/**
 * Mints a company {@code company_code} (ADR 0054): 6 chars from the
 * Crockford-base32-minus-ambiguous alphabet {@code [23456789abcdefghjkmnpqrstvwxyz]} (no {@code
 * 0/1/i/l/o/u}) — unambiguous when an owner reads it aloud to staff.
 *
 * <p>The ~30^6 (≈ 7×10^8) space makes a collision rare; when one does occur it is caught by the
 * {@code uq_company_company_code} UNIQUE index (a pre-SELECT is impossible under RLS — a company
 * can only ever see its own row), and {@link CompanyService} mints a fresh code and retries. The
 * alphabet MUST match the one in the {@code V12__company_code.sql} backfill and {@code
 * Company#COMPANY_CODE_ALPHABET}.
 */
final class CompanyCodeGenerator {

  private static final char[] ALPHABET = "23456789abcdefghjkmnpqrstvwxyz".toCharArray();
  private static final int CODE_LENGTH = 6;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private CompanyCodeGenerator() {}

  /**
   * A fresh 6-char code. NOT guaranteed globally unique — the caller retries on the UNIQUE index.
   */
  static String generate() {
    char[] out = new char[CODE_LENGTH];
    for (int i = 0; i < CODE_LENGTH; i++) {
      out[i] = ALPHABET[SECURE_RANDOM.nextInt(ALPHABET.length)];
    }
    return new String(out);
  }
}
