package id.co.nativeapp.employee.operator.dto;

import java.time.Instant;

/**
 * Response body for {@code POST /api/v1/operators/session} (ADR 0049 P1). {@code operatorSession}
 * is the self-contained, HMAC-signed operator token the till stores and later sends as {@code
 * X-Operator-Session} to a vertical (P2 — no vertical verifies it yet). {@code displayName} and
 * {@code role} are exactly what the till shows ("name + role", nothing more — rule 6, no NIK/
 * salary).
 *
 * @param operatorSession the opaque signed token ({@code libs/security OperatorTokenCodec} shape)
 * @param displayName the operator's full name
 * @param role the operator's role on the assignment that authorized this session
 * @param expiresAt when the token's {@code exp} claim falls
 */
public record OperatorSessionResponse(
    String operatorSession, String displayName, String role, Instant expiresAt) {}
