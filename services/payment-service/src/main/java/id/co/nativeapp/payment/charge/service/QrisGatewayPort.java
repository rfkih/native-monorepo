package id.co.nativeapp.payment.charge.service;

import id.co.nativeapp.payment.settings.domain.ProviderEnvironment;
import java.time.Instant;
import java.util.UUID;

/**
 * The provider-agnostic seam for dynamic-QRIS gateways (ADR 0045) — Midtrans first ({@code
 * MidtransClient}); a further provider is a new adapter behind this port, not a redesign. Every
 * call is an OUTBOUND integration edge (the gateway→Keycloak class ADR 0007 established), made
 * OUTSIDE any database transaction, authenticated with the MERCHANT's own decrypted server key —
 * which must live only in call-scope locals and never be logged (rule 6).
 */
public interface QrisGatewayPort {

  /** The merchant's decrypted credentials + environment, held call-scoped only. */
  record GatewayCredentials(String serverKey, ProviderEnvironment environment) {

    /** Redacted — a credentials record must never leak the key into a log line (rule 6). */
    @Override
    public String toString() {
      return "GatewayCredentials[serverKey=***REDACTED***, environment=" + environment + "]";
    }
  }

  /** A successful QR creation: what the till renders + when it dies. */
  record QrCreated(String qrString, String qrUrl, String providerTxnId, Instant expiresAt) {}

  /** The provider's view of a charge, normalized. */
  enum RemoteStatus {
    /** Customer has not paid yet. */
    PENDING,
    /** Money moved — the settle signal (Midtrans {@code settlement}, or {@code capture}+accept). */
    SETTLED,
    EXPIRED,
    CANCELED,
    /** Denied/failure at the provider. */
    FAILED,
    /** The provider does not know this order (e.g. the create call never reached it). */
    NOT_FOUND
  }

  /** A status probe result. */
  record RemoteCharge(RemoteStatus status, String providerTxnId) {}

  /** The outcome of a cancel attempt. */
  enum CancelOutcome {
    CANCELED,
    /** The customer already paid — the caller must run the settlement path instead. */
    ALREADY_SETTLED,
    NOT_FOUND
  }

  /** The outcome of a side-effect-free credential probe ({@link #verify}). */
  enum GatewayVerification {
    /** The provider authenticated the key (the probe order is simply unknown). */
    VALID,
    /** The provider rejected the key (401/403) — wrong key, or a key for the other environment. */
    INVALID,
    /** The provider could not be reached / gave an inconclusive answer — try again later. */
    UNREACHABLE
  }

  /**
   * Creates a QRIS charge at the provider.
   *
   * @param credentials the merchant's own credentials
   * @param companyId the tenant — stamped into the per-charge webhook callback URL
   * @param providerOrderId our order id ({@code n-<chargeId>})
   * @param amountMinor the charge amount, minor units (IDR)
   * @throws id.co.nativeapp.payment.charge.domain.GatewayUnavailableException on
   *     timeout/5xx/contract violation
   */
  QrCreated createQr(
      GatewayCredentials credentials, UUID companyId, String providerOrderId, long amountMinor);

  /** Probes the charge's status at the provider (the no-webhook reconcile fallback). */
  RemoteCharge status(GatewayCredentials credentials, String providerOrderId);

  /** Cancels (or expires) the charge at the provider. */
  CancelOutcome cancel(GatewayCredentials credentials, String providerOrderId);

  /**
   * Verifies that {@code credentials} authenticate against the provider WITHOUT creating a charge
   * (ADR 0045 amendment — the "Test connection" affordance): a status probe of a throwaway order id
   * that the provider does not know. An authenticated key gets an order-not-found answer ({@link
   * GatewayVerification#VALID}); a rejected key gets 401/403 ({@link GatewayVerification#INVALID});
   * anything else is {@link GatewayVerification#UNREACHABLE}.
   */
  GatewayVerification verify(GatewayCredentials credentials);
}
