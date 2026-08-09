package id.co.nativeapp.restaurant.sale.domain;

import java.util.UUID;

/**
 * Thrown when a verified {@code X-Operator-Session} token (signature + {@code exp} already checked
 * OFFLINE by {@code libs/security}'s {@code OperatorSessionFilter}) names a {@code companyId} or
 * {@code businessId} that does NOT match the bound tenant / the sale's own outlet (ADR 0049 P2).
 *
 * <p>This can only happen with a stolen, stale, or misdirected token replayed against the wrong
 * company/outlet — the signature alone proves the token is authentic, not that it applies HERE. It
 * must NEVER silently attribute the sale under the wrong id, and must NEVER silently fall back to
 * the device actor either (that would misattribute commission just as badly, only quietly) — so
 * {@code SaleWriter} rejects the whole write outright. HTTP response is {@code 409 Conflict}
 * RFC-7807 ({@code https://errors.nativeapp.id/operator-mismatch}): the request cannot be completed
 * with this token; the cashier must re-select the operator (which mints a fresh, correctly-scoped
 * token) and retry.
 */
public class OperatorMismatchException extends RuntimeException {

  /** Stable RFC-7807 problem type URI for this error — the UI maps it to an i18n key. */
  public static final String TYPE = "https://errors.nativeapp.id/operator-mismatch";

  private final String expectedCompanyId;
  private final String tokenCompanyId;
  private final UUID expectedBusinessId;
  private final UUID tokenBusinessId;

  public OperatorMismatchException(
      String expectedCompanyId,
      String tokenCompanyId,
      UUID expectedBusinessId,
      UUID tokenBusinessId) {
    super(
        "Operator session token company/outlet does not match the bound tenant/outlet for this"
            + " sale — refusing to attribute it");
    this.expectedCompanyId = expectedCompanyId;
    this.tokenCompanyId = tokenCompanyId;
    this.expectedBusinessId = expectedBusinessId;
    this.tokenBusinessId = tokenBusinessId;
  }

  /** The bound tenant this write was scoped to. */
  public String getExpectedCompanyId() {
    return expectedCompanyId;
  }

  /** The operator-session token's {@code companyId} claim. */
  public String getTokenCompanyId() {
    return tokenCompanyId;
  }

  /** The outlet (business unit) this sale is being recorded against. */
  public UUID getExpectedBusinessId() {
    return expectedBusinessId;
  }

  /** The operator-session token's {@code businessId} claim. */
  public UUID getTokenBusinessId() {
    return tokenBusinessId;
  }

  /**
   * Asserts a verified operator-session token applies HERE — its {@code companyId} matches the
   * bound tenant AND its {@code businessId} matches this sale's own outlet — throwing {@link
   * OperatorMismatchException} otherwise. This is the SINGLE tenant/outlet binding for an operator
   * token: the HMAC signature (checked offline by {@code OperatorSessionFilter}) proves the token
   * is authentic <em>fleet-wide</em>, NOT that it belongs to this company/outlet. So every consumer
   * that stamps the operator as the seller MUST call this first — both the synchronous sale path
   * ({@code SaleWriter}) and the digital-tender PENDING stamp point ({@code
   * PaymentWriter#recordPendingDigitalInCurrentTx}, ADR 0049 P4) — so the two can never diverge,
   * and a stored ring-time seller is guaranteed already-validated when async capture reads it back.
   *
   * <p>Takes the raw claim values (not the {@code libs/security OperatorPrincipal}) so this {@code
   * domain} class stays free of any web/security dependency.
   *
   * @throws OperatorMismatchException if {@code tokenCompanyId}/{@code tokenBusinessId} do not
   *     match {@code boundCompanyId}/{@code businessId}
   */
  public static void requireMatch(
      String tokenCompanyId, UUID tokenBusinessId, String boundCompanyId, UUID businessId) {
    if (!tokenCompanyId.equals(boundCompanyId) || !tokenBusinessId.equals(businessId)) {
      throw new OperatorMismatchException(
          boundCompanyId, tokenCompanyId, businessId, tokenBusinessId);
    }
  }
}
