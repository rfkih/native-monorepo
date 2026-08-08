package id.co.nativeapp.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;

/**
 * The operator-session token format (ADR 0049 P1) — mirrors the fleet's self-order QR token codec
 * ({@code restaurant-service selforderaccess.domain.SelfOrderTokenCodec}) for the signing
 * mechanics, applied to a per-employee PIN-verified cashier session instead of a per-outlet QR
 * code:
 *
 * <pre>{@code base64url(payloadJson) + "." + base64url(HMAC_SHA256(signingKey, payloadBytes))}
 * </pre>
 *
 * <p>{@code payloadJson} is the {@link OperatorTokenPayload} record serialized via Jackson 3
 * ({@code tools.jackson.databind} — the Spring Boot 4 / Framework 7 Jackson generation, already on
 * the classpath fleet-wide via {@code spring-boot-starter-jackson} — no new dependency; note this
 * differs from the classic {@code com.fasterxml.jackson.databind} the older {@code
 * SelfOrderTokenCodec} uses, which reaches restaurant-service's classpath only transitively).
 * Unlike the self-order per-outlet secret, the HMAC key here is the single {@code
 * native.operator-token.signing-key} shared by employee-service (the minter) and every vertical
 * (the P2 offline verifiers) — this codec lives in {@code libs/security}, NOT employee-service,
 * precisely so each vertical can decode/verify a token without ever calling employee-service
 * synchronously (CLAUDE.md rule 2).
 *
 * <p>This is a pure, stateless, dependency-light primitive on purpose: no Spring, only Jackson +
 * {@code javax.crypto} + the JDK, so it can be linked into any service's classpath cheaply.
 *
 * <p><strong>Expiry is carried, not enforced here.</strong> Neither {@link
 * #decodePayloadUnverified} nor {@link #verifySignature} rejects an expired {@code exp} claim —
 * checking {@code exp} against the current time is the verifier's job (the P2 vertical filter, or
 * employee-service's own future use), so this codec stays a pure encode/decode/verify primitive
 * with no clock dependency.
 *
 * <p><strong>Constant-time signature comparison.</strong> {@link #verifySignature} uses {@link
 * MessageDigest#isEqual(byte[], byte[])}, which runs in time independent of where the first
 * mismatching byte occurs — an ordinary {@code Arrays.equals}/{@code byte[].equals} short-circuits
 * on the first difference and would leak the correct signature one byte at a time to a timing
 * attacker.
 */
public final class OperatorTokenCodec {

  private static final String ALGORITHM = "HmacSHA256";
  private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
  private static final ObjectMapper JSON = new ObjectMapper();

  private OperatorTokenCodec() {}

  /**
   * Encodes a signed token: {@code base64url(payloadJson) + "." + base64url(HMAC-SHA256(secret,
   * payloadBytes))}.
   *
   * @param payload the claims to sign
   * @param secretBytes the raw signing-key bytes (the decoded {@code
   *     native.operator-token.signing-key}) — the HMAC key
   */
  public static String encode(OperatorTokenPayload payload, byte[] secretBytes) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(secretBytes, "secretBytes");
    byte[] payloadBytes = toJsonBytes(payload);
    byte[] signature = hmac(secretBytes, payloadBytes);
    return URL_ENCODER.encodeToString(payloadBytes) + "." + URL_ENCODER.encodeToString(signature);
  }

  /**
   * Parses (WITHOUT verifying) a token's payload. A caller MUST NOT trust the returned claims for
   * anything security-relevant until {@link #verifySignature} has also passed.
   *
   * @throws OperatorTokenMalformedException if the token is not well-formed ({@code
   *     payload.signature}, valid base64url, valid JSON matching the payload shape, every required
   *     claim present)
   */
  public static OperatorTokenPayload decodePayloadUnverified(String token) {
    String[] parts = splitToken(token);
    byte[] payloadBytes;
    try {
      payloadBytes = URL_DECODER.decode(parts[0]);
    } catch (IllegalArgumentException e) {
      throw new OperatorTokenMalformedException("Token payload segment is not valid base64url", e);
    }
    OperatorTokenPayload payload;
    try {
      payload = JSON.readValue(payloadBytes, OperatorTokenPayload.class);
    } catch (Exception e) {
      throw new OperatorTokenMalformedException("Token payload is not valid JSON", e);
    }
    if (payload == null
        || payload.v() != OperatorTokenPayload.CURRENT_VERSION
        || payload.companyId() == null
        || payload.companyId().isBlank()
        || payload.businessId() == null
        || payload.operatorUserId() == null
        || payload.operatorUserId().isBlank()
        || payload.operatorEmployeeId() == null
        || payload.displayName() == null
        || payload.displayName().isBlank()
        || payload.role() == null
        || payload.role().isBlank()
        || payload.jti() == null
        || payload.jti().isBlank()) {
      throw new OperatorTokenMalformedException("Token payload is missing required claims", null);
    }
    return payload;
  }

  /**
   * Verifies a token's signature against the configured signing key, constant-time. Recomputes the
   * HMAC over the EXACT payload bytes carried on the wire (not a re-serialization of the parsed
   * record), so any canonicalization drift can never cause a false accept/reject.
   *
   * @param token the full {@code payload.signature} token string
   * @param secretBytes the raw signing-key bytes — the HMAC key
   * @return {@code true} iff the token is well-formed AND its signature matches
   */
  public static boolean verifySignature(String token, byte[] secretBytes) {
    Objects.requireNonNull(secretBytes, "secretBytes");
    String[] parts;
    try {
      parts = splitToken(token);
    } catch (OperatorTokenMalformedException malformed) {
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
      throw new OperatorTokenMalformedException("Token is missing", null);
    }
    String[] parts = token.split("\\.", 2);
    if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
      throw new OperatorTokenMalformedException("Token must be exactly 'payload.signature'", null);
    }
    return parts;
  }

  private static byte[] toJsonBytes(OperatorTokenPayload payload) {
    try {
      return JSON.writeValueAsBytes(payload);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize an operator token payload", e);
    }
  }

  private static byte[] hmac(byte[] secretBytes, byte[] payloadBytes) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secretBytes, ALGORITHM));
      return mac.doFinal(payloadBytes);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to compute an operator token HMAC", e);
    }
  }

  /**
   * Thrown when a token cannot be parsed into an {@link OperatorTokenPayload} at all (garbage,
   * truncated, wrong shape, a missing required claim) — a verifier maps this to the same uniform
   * {@code 401} as a signature mismatch (never distinguished in the response, so a probing attacker
   * cannot learn which failure mode they hit).
   */
  public static final class OperatorTokenMalformedException extends RuntimeException {
    OperatorTokenMalformedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
