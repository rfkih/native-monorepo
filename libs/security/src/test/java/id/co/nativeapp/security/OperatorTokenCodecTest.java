package id.co.nativeapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.security.OperatorTokenCodec.OperatorTokenMalformedException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit unit tests for {@link OperatorTokenCodec} (ADR 0049 P1) — no Spring context needed,
 * mirroring the fleet's self-order codec test posture (ENGINEERING-STANDARDS §"boot the least
 * context that proves the assertion").
 */
class OperatorTokenCodecTest {

  private static final byte[] SECRET =
      "a-32-byte-test-operator-signing-key".getBytes(StandardCharsets.UTF_8);
  private static final byte[] OTHER_SECRET =
      "a-different-32-byte-signing-key!!!!".getBytes(StandardCharsets.UTF_8);

  private static OperatorTokenPayload samplePayload() {
    return new OperatorTokenPayload(
        OperatorTokenPayload.CURRENT_VERSION,
        "11111111-1111-1111-1111-111111111111",
        UUID.randomUUID(),
        "keycloak-sub-abc123",
        UUID.randomUUID(),
        "Budi Santoso",
        "cashier",
        1_000L,
        2_000L,
        UUID.randomUUID().toString());
  }

  @Test
  void encodeThenDecodeAndVerifyRoundTripsEveryClaim() {
    OperatorTokenPayload payload = samplePayload();

    String token = OperatorTokenCodec.encode(payload, SECRET);
    OperatorTokenPayload decoded = OperatorTokenCodec.decodePayloadUnverified(token);

    assertThat(decoded).isEqualTo(payload);
    assertThat(OperatorTokenCodec.verifySignature(token, SECRET)).isTrue();
  }

  @Test
  void operatorUserIdBusinessIdAndExpRoundTripExactly() {
    OperatorTokenPayload payload = samplePayload();

    String token = OperatorTokenCodec.encode(payload, SECRET);
    OperatorTokenPayload decoded = OperatorTokenCodec.decodePayloadUnverified(token);

    assertThat(decoded.operatorUserId()).isEqualTo(payload.operatorUserId());
    assertThat(decoded.businessId()).isEqualTo(payload.businessId());
    assertThat(decoded.exp()).isEqualTo(payload.exp());
  }

  @Test
  void aTamperedSignatureFailsVerification() {
    String token = OperatorTokenCodec.encode(samplePayload(), SECRET);
    String[] parts = token.split("\\.", 2);
    // Flip the first character of the signature segment — still valid base64url, wrong bytes.
    char flipped = parts[1].charAt(0) == 'A' ? 'B' : 'A';
    String tamperedSignature = flipped + parts[1].substring(1);
    String tampered = parts[0] + "." + tamperedSignature;

    assertThat(OperatorTokenCodec.verifySignature(tampered, SECRET)).isFalse();
  }

  @Test
  void theWrongKeyFailsVerification() {
    String token = OperatorTokenCodec.encode(samplePayload(), SECRET);

    assertThat(OperatorTokenCodec.verifySignature(token, OTHER_SECRET)).isFalse();
  }

  @Test
  void aMalformedTokenThrowsOnDecode() {
    assertThatThrownBy(() -> OperatorTokenCodec.decodePayloadUnverified("not-a-real-token"))
        .isInstanceOf(OperatorTokenMalformedException.class);
    assertThatThrownBy(() -> OperatorTokenCodec.decodePayloadUnverified(""))
        .isInstanceOf(OperatorTokenMalformedException.class);
    assertThatThrownBy(() -> OperatorTokenCodec.decodePayloadUnverified(null))
        .isInstanceOf(OperatorTokenMalformedException.class);
  }

  @Test
  void aMalformedTokenFailsVerificationRatherThanThrowing() {
    assertThat(OperatorTokenCodec.verifySignature("garbage", SECRET)).isFalse();
    assertThat(OperatorTokenCodec.verifySignature("a.b", SECRET)).isFalse();
  }

  @Test
  void decodingRejectsAPayloadMissingARequiredClaim() {
    // v=1 but companyId blank — a required claim missing.
    OperatorTokenPayload payload =
        new OperatorTokenPayload(
            OperatorTokenPayload.CURRENT_VERSION,
            "",
            UUID.randomUUID(),
            "sub",
            UUID.randomUUID(),
            "Name",
            "cashier",
            1L,
            2L,
            "jti");
    String token = OperatorTokenCodec.encode(payload, SECRET);

    assertThatThrownBy(() -> OperatorTokenCodec.decodePayloadUnverified(token))
        .isInstanceOf(OperatorTokenMalformedException.class);
  }

  @Test
  void decodingRejectsAnUnsupportedVersion() {
    OperatorTokenPayload payload =
        new OperatorTokenPayload(
            99,
            "11111111-1111-1111-1111-111111111111",
            UUID.randomUUID(),
            "sub",
            UUID.randomUUID(),
            "Name",
            "cashier",
            1L,
            2L,
            "jti");
    String token = OperatorTokenCodec.encode(payload, SECRET);

    assertThatThrownBy(() -> OperatorTokenCodec.decodePayloadUnverified(token))
        .isInstanceOf(OperatorTokenMalformedException.class);
  }
}
