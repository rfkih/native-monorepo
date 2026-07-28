package id.co.nativeapp.org.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/users} — invite a teammate to the caller's company.
 *
 * <p>{@code role} is the primary business role; {@code additionalRoles} (optional) grants further
 * roles on the same login — e.g. an employee login that can also run the POS is {@code role:
 * "employee", additionalRoles: ["cashier"]}. Every role is validated against the same whitelist in
 * the service layer (stable RFC-7807 type URI on rejection).
 *
 * <p>The company id is NEVER taken from the request body; it comes exclusively from {@code
 * TenantContext.require().companyId()} (rule 5 — caller tenant is un-spoofable).
 *
 * @param email the invitee's email address — must be a syntactically valid email
 * @param role the primary business role to assign
 * @param additionalRoles further roles for the same login, or null/empty for just the primary
 */
public record InviteUserRequest(
    @NotBlank @Email String email,
    @NotBlank String role,
    @Size(max = 4) List<String> additionalRoles) {}
