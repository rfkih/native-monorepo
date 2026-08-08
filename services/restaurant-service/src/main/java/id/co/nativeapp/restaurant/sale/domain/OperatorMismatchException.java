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
}
