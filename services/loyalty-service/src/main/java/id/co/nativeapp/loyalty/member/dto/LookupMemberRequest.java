package id.co.nativeapp.loyalty.member.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/v1/loyalty/members/lookup} request body (security review W-2). The phone travels
 * in the request BODY, never a query parameter — a {@code GET .../members?phone=...} would land the
 * full phone number in proxy access logs and OTel span tags (PII, rule 6), which a request body
 * does not. See {@code MemberController} class javadoc for the removed-GET rationale.
 *
 * @param phone the raw, cashier-entered phone number (normalized server-side — see {@code
 *     member.domain.PhoneNormalizer})
 */
public record LookupMemberRequest(@NotBlank String phone) {}
