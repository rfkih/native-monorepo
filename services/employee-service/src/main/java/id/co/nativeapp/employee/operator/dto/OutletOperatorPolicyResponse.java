package id.co.nativeapp.employee.operator.dto;

/**
 * Response body for {@code GET /api/v1/operators/policy?businessId=} (ADR 0049) — whether the
 * outlet's operator sign-in currently requires a PIN. An outlet with no policy row on file reads as
 * {@code true} (the safe default, today's behavior) — see {@code
 * operator.service.OutletOperatorPolicyReader}.
 *
 * @param requirePin whether {@code POST /api/v1/operators/session} must verify a PIN for this
 *     outlet
 */
public record OutletOperatorPolicyResponse(boolean requirePin) {}
