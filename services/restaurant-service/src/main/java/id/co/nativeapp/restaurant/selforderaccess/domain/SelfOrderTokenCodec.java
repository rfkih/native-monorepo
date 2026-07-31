package id.co.nativeapp.restaurant.selforderaccess.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The self-order QR token format (Phase 6, ADR 0029) — mirrors the fleet's keyed-HMAC gift-card
 * -code precedent ({@code giftcard.domain.GiftCardCodeGenerator}) for the signing mechanics, applied
 * to a compact bearer token instead of a short display code:
 *
 * <pre>{@code base64url(payloadJson) + "." + base64url(HMAC_SHA256(outletSecret, payloadBytes))}</pre>
 *
 * <p>{@code payloadJson} is the {@link SelfOrderTokenPayload} record serialized via Jackson (already
 * on the classpath fleet-wide for JSON web serialization — no new dependency). Unlike the fleet's
 * fleet-shared gift-card key, the HMAC key here is the PER-(company, outlet) secret stored (AES-256-
 * GCM encrypted) on the token's {@code self_order_access} row — so verifying a token requires
 * looking that row up first (see {@code selforderaccess.service.SelfOrderAccessReader#verify}).
 *
 * <p><strong>No expiry.</strong> A printed table tent cannot refresh itself, so the token carries no
 * {@code exp} claim; revocation is rotating the outlet's {@code self_order_access} row (a new {@code
 * kid} + secret, the old row RETIRED) — every token signed by the retired secret stops verifying
 * immediately regardless of how long ago it was printed.
 *
 * <p><strong>Constant-time signature comparison.</strong> {@link #verifySignature} uses {@link
 * MessageDigest#isEqual(byte[], byte[])}, which runs in time independent of where the first
 * mismatching byte occurs — an ordinary {@code Arrays.equals}/{@code byte[].equals} short-circuits
 * on the first difference and would leak the correct signature one byte at a time to a timing
 * attacker.
 */
public final class SelfOrderTokenCodec {

  private static final String ALGORITHM = "HmacSHA256";
  private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
  private static final ObjectMapper JSON = new ObjectMapper();

  private SelfOrderTokenCodec() {}

  /**
   * Encodes a signed token: {@code base64url(payloadJson) + "." + base64url(HMAC-SHA256(secret,
   * payloadBytes))}.
   *
   * @param payload the claims to sign
   * @param secretBytes the outlet's raw (decrypted) secret bytes — the HMAC key
   */
  public static String encode(SelfOrderTokenPayload payload, byte[] secretBytes) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(secretBytes, "secretBytes");
    byte[] payloadBytes = toJsonBytes(payload);
    byte[] signature = hmac(secretBytes, payloadBytes);
    return URL_ENCODER.encodeToString(payloadBytes) + "." + URL_ENCODER.encodeToString(signature);
  }

  /**
   * Parses (WITHOUT verifying) a token's payload — the first step of the filter's verification
   * recipe: the claimed {@code companyId} is read here so a tenant scope can be bound BEFORE the
   * RLS-scoped {@code self_order_access} lookup that actually proves the token authentic. NEVER
   * trust the returned payload's claims for anything beyond that lookup until {@link
   * #verifySignature} has also passed.
   *
   * @throws SelfOrderTokenMalformedException if the token is not well-formed ({@code
   *     payload.signature}, valid base64url, valid JSON matching the payload shape)
   */
  public static SelfOrderTokenPayload decodePayloadUnverified(String token) {
    String[] parts = splitToken(token);
    byte[] payloadBytes;
    try {
      payloadBytes = URL_DECODER.decode(parts[0]);
    } catch (IllegalArgumentException e) {
      throw new SelfOrderTokenMalformedException("Token payload segment is not valid base64url", e);
    }
    SelfOrderTokenPayload payload;
    try {
      payload = JSON.readValue(payloadBytes, SelfOrderTokenPayload.class);
    } catch (Exception e) {
      throw new SelfOrderTokenMalformedException("Token payload is not valid JSON", e);
    }
    if (payload == null
        || payload.v() != SelfOrderTokenPayload.CURRENT_VERSION
        || payload.kid() == null
        || payload.kid().isBlank()
        || payload.companyId() == null
        || payload.companyId().isBlank()
        || payload.businessId() == null
        || payload.outletId() == null) {
      throw new SelfOrderTokenMalformedException("Token payload is missing required claims", null);
    }
    return payload;
  }

  /**
   * Verifies a token's signature against the given (already-resolved) outlet secret, constant-time.
   * Recomputes the HMAC over the EXACT payload bytes carried on the wire (not a re-serialization of
   * the parsed record), so any canonicalization drift can never cause a false accept/reject.
   *
   * @param token the full {@code payload.signature} token string
   * @param secretBytes the outlet's raw (decrypted) secret bytes — the HMAC key
   * @return {@code true} iff the token is well-formed AND its signature matches
   */
  public static boolean verifySignature(String token, byte[] secretBytes) {
    Objects.requireNonNull(secretBytes, "secretBytes");
    String[] parts;
    try {
      parts = splitToken(token);
    } catch (SelfOrderTokenMalformedException malformed) {
      return false;
    }
    byte[] payloadBytes;
    byte[] claimedSignature;
    try {
      payloadBytes = URL_DECODER.decode(parts[0]);
      claimedSignature = URL_DECODER.decode(parts[1]);
    } catch (IllegalArgumentException notBase64) {
      return false;
    }
    byte[] expectedSignature = hmac(secretBytes, payloadBytes);
    return MessageDigest.isEqual(expectedSignature, claimedSignature);
  }

  private static String[] splitToken(String token) {
    if (token == null || token.isBlank()) {
      throw new SelfOrderTokenMalformedException("Token is missing", null);
    }
    String[] parts = token.split("\\.", 2);
    if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
      throw new SelfOrderTokenMalformedException(
          "Token must be exactly 'payload.signature'", null);
    }
    return parts;
  }

  private static byte[] toJsonBytes(SelfOrderTokenPayload payload) {
    try {
      return JSON.writeValueAsBytes(payload);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize a self-order token payload", e);
    }
  }

  private static byte[] hmac(byte[] secretBytes, byte[] payloadBytes) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secretBytes, ALGORITHM));
      return mac.doFinal(payloadBytes);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to compute a self-order token HMAC", e);
    }
  }

  /**
   * Thrown when a token cannot be parsed into a {@link SelfOrderTokenPayload} at all (garbage,
   * truncated, wrong shape) — always maps to {@code 401} at the filter, exactly like a signature
   * mismatch (never distinguished in the response, so a probing attacker cannot learn which failure
   * mode they hit).
   */
  public static final class SelfOrderTokenMalformedException extends RuntimeException {
    SelfOrderTokenMalformedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
