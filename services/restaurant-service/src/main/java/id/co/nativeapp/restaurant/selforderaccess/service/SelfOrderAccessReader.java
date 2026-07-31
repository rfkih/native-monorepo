package id.co.nativeapp.restaurant.selforderaccess.service;

import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderAccess;
import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderTokenCodec;
import id.co.nativeapp.restaurant.selforderaccess.repository.SelfOrderAccessRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the {@code self_order_access} feature: the currently-active row for an outlet (the
 * management-mint path), and the anonymous-token verification lookup (the {@code
 * config.SelfOrderTokenFilter} path).
 *
 * <p>A {@code @Transactional(readOnly = true)} bean so the proxy + auto-RLS aspect engage — both
 * callers rely on RLS to scope the row to the bound tenant (rule 5): the management path via the
 * request's own bound tenant, the anonymous path via the tenant the filter bound from the token's
 * UNVERIFIED {@code companyId} claim (see {@link #verify} javadoc).
 *
 * <p>{@code @Component}, not {@code @Service} — see {@code entitlement.service
 * .EntitlementProjectionReader}'s javadoc for why (restaurant-service's stricter ArchUnit rule).
 */
@Component
public class SelfOrderAccessReader {

  private final SelfOrderAccessRepository repository;

  public SelfOrderAccessReader(SelfOrderAccessRepository repository) {
    this.repository = repository;
  }

  /** The outlet's currently ACTIVE row (RLS-scoped), if any. Used by the management mint path. */
  @Transactional(readOnly = true)
  public Optional<SelfOrderAccess> findActive(UUID outletId) {
    TenantContext.require();
    return repository.findByOutletIdAndStatus(outletId, SelfOrderAccess.STATUS_ACTIVE);
  }

  /**
   * The anonymous token-verification lookup — the security-critical read {@code
   * config.SelfOrderTokenFilter} calls AFTER binding a tentative tenant scope from the token's
   * UNVERIFIED payload claims (per the filter's documented recipe: parse {@code companyId} first,
   * bind {@link TenantContext}, THEN load the row RLS-scoped and verify).
   *
   * <p>Because the lookup is RLS-scoped to that tentatively-bound {@code companyId}, a token whose
   * claimed company does not actually own the {@code (outletId, kid)} row it names finds NOTHING —
   * RLS makes the real owner's row invisible under the wrong tenant — so a forged/cross-tenant
   * {@code companyId} claim can never pass verification. A retired {@code kid} or an unknown
   * {@code kid} likewise resolve to nothing (only {@code ACTIVE} rows are matched). Only once a row
   * IS found does {@link SelfOrderTokenCodec#verifySignature} run — constant-time — against that
   * row's decrypted secret, so signature verification always occurs against the secret whose company
   * actually owns it.
   *
   * @return the verified {@link SelfOrderAccess} row iff a matching ACTIVE row exists AND the
   *     token's HMAC signature verifies against its secret; {@link Optional#empty()} on ANY mismatch
   *     (garbage token, retired/unknown kid, cross-outlet/cross-tenant claim, or a bad signature) —
   *     the filter maps every case to a uniform {@code 401} so a probing attacker cannot distinguish
   *     failure modes.
   */
  @Transactional(readOnly = true)
  public Optional<SelfOrderAccess> verify(UUID outletId, String kid, String token) {
    TenantContext.require();
    return repository
        .findByOutletIdAndKidAndStatus(outletId, kid, SelfOrderAccess.STATUS_ACTIVE)
        .filter(access -> SelfOrderTokenCodec.verifySignature(token, decode(access)));
  }

  private static byte[] decode(SelfOrderAccess access) {
    return Base64.getDecoder().decode(access.getSecretPlaintext());
  }
}
