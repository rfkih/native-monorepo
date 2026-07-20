package id.co.nativeapp.org.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/users} — invite a teammate to the caller's company.
 *
 * <p>Both fields are required. The {@code role} must be one of {@code owner}, {@code manager},
 * {@code cashier} — validated in the service layer (allows a stable RFC-7807 type URI on rejection,
 * rather than a generic bean-validation 400).
 *
 * <p>The company id is NEVER taken from the request body; it comes exclusively from {@code
 * TenantContext.require().companyId()} (rule 5 — caller tenant is un-spoofable).
 *
 * @param email the invitee's email address — must be a syntactically valid email
 * @param role the business role to assign ({@code owner}/{@code manager}/{@code cashier})
 */
public record InviteUserRequest(@NotBlank @Email String email, @NotBlank String role) {}
