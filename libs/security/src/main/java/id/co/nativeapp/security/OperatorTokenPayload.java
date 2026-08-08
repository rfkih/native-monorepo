package id.co.nativeapp.security;

import java.util.UUID;

/**
 * The operator-session token's decoded claims (ADR 0049 P1): {@code {v, companyId, businessId,
 * operatorUserId, operatorEmployeeId, displayName, role, iat, exp, jti}}. Minted by
 * employee-service's {@code POST /api/v1/operators/session} after a PIN verification, and — from P2
 * onward — verified OFFLINE inside each vertical (restaurant/carwash/barbershop) so the seller of a
 * till sale can be recaptured without a synchronous call to employee-service (CLAUDE.md rule 2).
 *
 * <p>Claims stay minimal on purpose (rule 6): only a display name and a role — never NIK, salary,
 * or any other PII.
 *
 * @param v the payload version (currently always {@code 1}; a verifier rejects any other value —
 *     leaves room to evolve the claim shape without breaking an already-minted token)
 * @param companyId the owning tenant; a verifier binds/checks {@link
 *     id.co.nativeapp.tenant.TenantContext} against this claim ONLY after the signature has
 *     verified — never trusted standing alone
 * @param businessId the outlet (== business unit id, ADR 0012 flat org tree) the session was minted
 *     for; a verifier asserts this matches the sale's own outlet
 * @param operatorUserId the operator's Keycloak subject id ({@code employee.user_id}) — the id
 *     {@code Sale.sold_by_user_id} / {@code MetricPublished.subject} is stamped from (ADR 0049),
 *     never a client-supplied value
 * @param operatorEmployeeId the operator's employee-service {@code employee.id}
 * @param displayName the operator's full name, for the till's "name + role" display — NOT PII (rule
 *     6 — no NIK/salary on this token)
 * @param role the operator's role on the assignment that authorized this session (e.g. {@code
 *     cashier}, {@code manager})
 * @param iat the mint time, epoch seconds
 * @param exp the expiry time, epoch seconds — carried but NOT checked by {@link
 *     OperatorTokenCodec}; the P2 verifier enforces it
 * @param jti a fresh random id for this token (one per mint) — a hook for future replay/audit
 *     tracking, not currently deduplicated
 */
public record OperatorTokenPayload(
    int v,
    String companyId,
    UUID businessId,
    String operatorUserId,
    UUID operatorEmployeeId,
    String displayName,
    String role,
    long iat,
    long exp,
    String jti) {

  /** The only supported payload version today. */
  public static final int CURRENT_VERSION = 1;
}
