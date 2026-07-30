package id.co.nativeapp.loyalty.member.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/v1/loyalty/members} request body. {@code phone} is the raw, cashier-entered
 * phone number (normalized server-side — see {@code member.domain.PhoneNormalizer}); {@code
 * displayName} is optional. Neither {@code company_id} nor the actor is a field here — both come
 * from the bound {@code TenantContext} (rule 5).
 */
public record EnrollMemberRequest(@NotBlank String phone, String displayName) {}
