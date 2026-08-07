package id.co.nativeapp.payment.charge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.payment.charge.domain.ChargeStatus;
import id.co.nativeapp.payment.charge.domain.PaymentCharge;
import id.co.nativeapp.payment.charge.domain.WebhookRejectedException;
import id.co.nativeapp.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Processes the inbound Midtrans settlement notification (ADR 0045 — the fleet's second anonymous
 * edge, the ADR 0029 recipe in webhook form). The tenant is a PROVISIONAL claim from the callback
 * URL's {@code {companyId}} (we stamped it into the per-charge {@code X-Override-Notification}
 * URL): everything after the bind is RLS-scoped, so a forged company id sees no settings row and is
 * rejected exactly like a bad signature — uniformly, with no oracle.
 *
 * <p><strong>Authentication.</strong> Midtrans signs every notification: {@code signature_key ==
 * sha512hex(order_id + status_code + gross_amount + serverKey)}. The key is the MERCHANT's own
 * server key (decrypted call-scoped, rule 6); the comparison is constant-time ({@link
 * MessageDigest#isEqual}).
 *
 * <p><strong>Park, don't drop.</strong> Once authenticated, every anomaly parks in the error inbox
 * and answers 200 (stopping Midtrans's retries — a human owns it): an unknown order id (a foreign
 * transaction on the merchant's shared Midtrans account), a gross_amount that does not exactly
 * equal the charge's amount, and a settlement arriving AFTER a local cancel/expiry (real money
 * moved with no local capture — explicitly a human-refund case, never auto-captured).
 *
 * <p><strong>Exactly-once settle.</strong> A live charge settles via {@code
 * ChargeWriter#applySettlement} — the same optimistic-version-guarded transition the {@code /sync}
 * fallback uses, so a webhook/sync race commits ONE transition and ONE {@code
 * PaymentChargeSucceeded}; a duplicate notification finds the charge SUCCEEDED and no-ops.
 */
@Service
public class WebhookService {

  /** The provisional actor recorded on webhook-driven writes. */
  static final String WEBHOOK_ACTOR = "psp-webhook:midtrans";

  /**
   * A per-process random key used ONLY to keep the unknown-tenant rejection timing-uniform with a
   * bad-signature rejection (N1) — no caller can ever produce a signature that verifies against it,
   * and it never encrypts anything.
   */
  private static final String DUMMY_KEY =
      HexFormat.of()
          .formatHex(
              ((java.util.function.Supplier<byte[]>)
                      () -> {
                        byte[] bytes = new byte[32];
                        new java.security.SecureRandom().nextBytes(bytes);
                        return bytes;
                      })
                  .get());

  private static final String PARK_SOURCE_PREFIX = "payment.psp-webhook.";

  private final ChargeWriter writer;
  private final ErrorInboxWriter errorInbox;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public WebhookService(ChargeWriter writer, ErrorInboxWriter errorInbox) {
    this.writer = writer;
    this.errorInbox = errorInbox;
  }

  /**
   * Handles one Midtrans notification for the tenant claimed in the callback URL.
   *
   * @throws WebhookRejectedException malformed body / unknown-or-forged tenant / bad signature —
   *     the uniform 401 (never says which)
   */
  public void handleMidtrans(UUID companyId, String rawBody) {
    JsonNode body = parse(rawBody);
    String orderId = text(body, "order_id");
    String statusCode = text(body, "status_code");
    String grossAmount = text(body, "gross_amount");
    String signatureKey = text(body, "signature_key");
    if (orderId == null || statusCode == null || grossAmount == null || signatureKey == null) {
      throw new WebhookRejectedException();
    }

    try {
      TenantContext.callAs(
          companyId.toString(),
          WEBHOOK_ACTOR,
          () -> {
            process(
                companyId,
                orderId,
                statusCode,
                grossAmount,
                signatureKey,
                text(body, "transaction_status"),
                text(body, "fraud_status"),
                text(body, "transaction_id"));
            return null;
          });
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; process() only throws runtime exceptions.
      throw new IllegalStateException(e);
    }
  }

  private void process(
      UUID companyId,
      String orderId,
      String statusCode,
      String grossAmount,
      String signatureKey,
      String transactionStatus,
      String fraudStatus,
      String transactionId) {
    // A forged/unknown tenant sees no settings row under RLS — the same rejection as a bad
    // signature (uniform, no oracle). Timing-uniform too (security review N1): when no key
    // exists, the digest is still computed against a per-process RANDOM dummy key (which no
    // caller can ever satisfy), so "unknown tenant" and "bad signature" pay the same crypto cost
    // and reject at the same point.
    String serverKey = writer.companyServerKey().orElse(null);
    verifySignature(
        orderId, statusCode, grossAmount, serverKey != null ? serverKey : DUMMY_KEY, signatureKey);
    if (serverKey == null) {
      // Unreachable in practice (the random dummy never verifies) — a pure belt-and-suspenders
      // guard so a future refactor of verifySignature cannot silently open this path.
      throw new WebhookRejectedException();
    }

    Optional<PaymentCharge> chargeLookup = writer.chargeByProviderOrderId(orderId);
    if (chargeLookup.isEmpty()) {
      // Authentic (signed with THIS merchant's key) but not ours: a foreign transaction on the
      // merchant's shared Midtrans account. Park for a human; 200 stops the retries.
      park(companyId, "unknown-order", "Midtrans notified an unknown order_id " + orderId);
      return;
    }
    PaymentCharge charge = chargeLookup.get();

    // The signed gross_amount must equal OUR charge amount exactly — "<minor>.00" (IDR has zero
    // minor-unit digits). Anything else parks; it never captures.
    String expectedGross = charge.getAmountMinor() + ".00";
    if (!expectedGross.equals(grossAmount.strip())) {
      park(
          companyId,
          "amount-mismatch",
          "Midtrans gross_amount "
              + grossAmount
              + " does not match charge "
              + charge.getId()
              + " amount "
              + expectedGross);
      return;
    }

    QrisGatewayPort.RemoteStatus status =
        MidtransClient.normalizeStatus(transactionStatus, fraudStatus);
    switch (status) {
      case SETTLED -> settle(companyId, charge, transactionId);
      case EXPIRED -> writer.markExpired(charge.getId());
      case CANCELED -> writer.markCanceled(charge.getId());
      case FAILED -> writer.markFailed(charge.getId());
      case PENDING, NOT_FOUND -> {
        // Nothing to record yet.
      }
      default -> throw new IllegalStateException("Unhandled remote status: " + status);
    }
  }

  private void settle(UUID companyId, PaymentCharge charge, String transactionId) {
    if (charge.getStatus() == ChargeStatus.SUCCEEDED) {
      return; // Duplicate notification — absorbed, no second event.
    }
    if (!charge.isLive()) {
      // Real money moved AFTER the charge went CANCELED/EXPIRED/FAILED locally: never
      // auto-capture — a human refunds via the merchant's Midtrans dashboard (ADR 0045).
      parkLateSettlement(companyId, charge);
      return;
    }
    boolean applied = false;
    try {
      applied = writer.applySettlement(charge.getId(), transactionId, Instant.now());
    } catch (OptimisticLockingFailureException concurrentSettle) {
      // A concurrent sync/webhook transition won the version check — verify the winner below.
    }
    if (!applied) {
      // Code review C1: applySettlement declining is only a correct no-op when a CONCURRENT
      // settle already SUCCEEDED the charge. If instead the lazy expiry sweep (or a cancel) won
      // the race window between our stale pre-check and applySettlement's fresh read, real money
      // moved with NO capture and NO event — exactly the park-don't-drop case.
      PaymentCharge fresh = writer.chargeById(charge.getId());
      if (fresh.getStatus() != ChargeStatus.SUCCEEDED) {
        parkLateSettlement(companyId, fresh);
      }
    }
  }

  private void parkLateSettlement(UUID companyId, PaymentCharge charge) {
    park(
        companyId,
        "late-settlement",
        "Midtrans settled charge "
            + charge.getId()
            + " ("
            + charge.getAmountMinor()
            + " "
            + charge.getCurrency()
            + ") AFTER it went "
            + charge.getStatus()
            + " locally — refund the customer via the Midtrans dashboard");
  }

  private static void verifySignature(
      String orderId, String statusCode, String grossAmount, String serverKey, String provided) {
    String expected = sha512Hex(orderId + statusCode + grossAmount + serverKey);
    byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
    byte[] providedBytes =
        provided.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expectedBytes, providedBytes)) {
      throw new WebhookRejectedException();
    }
  }

  private static String sha512Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-512");
      return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // SHA-512 is a mandatory JDK algorithm — unreachable in practice.
      throw new IllegalStateException("SHA-512 MessageDigest unavailable", e);
    }
  }

  private void park(UUID companyId, String kind, String description) {
    // The description carries ids/amounts only — never key material, never the raw body (rule 6).
    errorInbox.record(
        new IllegalStateException(description),
        PARK_SOURCE_PREFIX + kind,
        companyId.toString(),
        null);
  }

  private JsonNode parse(String rawBody) {
    if (rawBody == null || rawBody.isBlank()) {
      throw new WebhookRejectedException();
    }
    try {
      return objectMapper.readTree(rawBody);
    } catch (com.fasterxml.jackson.core.JacksonException e) {
      throw new WebhookRejectedException();
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
