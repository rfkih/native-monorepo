package id.co.nativeapp.org.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/users} — invite a teammate/employee to the caller's company.
 *
 * <p>The login is keyed by {@code username} (required). {@code email} is OPTIONAL — some employees
 * have no email address — and is stored as contact metadata only, never as the login identifier.
 * (For an ordinary teammate invite the console simply sends the email as the username too.)
 *
 * <p>{@code role} is the primary business role; {@code additionalRoles} (optional) grants further
 * roles on the same login — e.g. an employee login that can also run the POS is {@code role:
 * "employee", additionalRoles: ["cashier"]}. Every role is validated against the same whitelist in
 * the service layer (stable RFC-7807 type URI on rejection).
 *
 * <p>The company id is NEVER taken from the request body; it comes exclusively from {@code
 * TenantContext.require().companyId()} (rule 5 — caller tenant is un-spoofable).
 *
 * @param username the login identifier (required) — non-whitespace, 3..254 chars; may be an email
 *     for an ordinary teammate, or a plain handle for an employee with no email
 * @param email the invitee's email address — OPTIONAL; validated as an email only when present
 * @param role the primary business role to assign
 * @param additionalRoles further roles for the same login, or null/empty for just the primary
 */
public record InviteUserRequest(
    @NotBlank @Pattern(regexp = "\\S{3,254}") String username,
    @Email @Size(max = 254) String email,
    @NotBlank String role,
    @Size(max = 4) List<String> additionalRoles) {}
