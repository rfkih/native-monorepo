package id.co.nativeapp.org.user.dto;

import java.util.List;

/**
 * Response body for a successful {@code POST /api/v1/users} ({@code 201 Created}).
 *
 * <p><strong>Credential hygiene.</strong> The {@code temporaryPassword} is returned ONCE here, on
 * creation, so the caller (the owner/manager) can share it with the invitee out of band. It is
 * NEVER stored server-side after this response is produced. The field name is intentionally
 * explicit so clients are not tempted to cache it. Do NOT log responses containing this field.
 *
 * @param id the Keycloak user UUID of the newly created user
 * @param username the login identifier
 * @param email the invitee's email address, or {@code null} if none was provided
 * @param role the primary assigned business role
 * @param roles every assigned business role (primary first)
 * @param temporaryPassword the one-time temporary password — NEVER log or cache this
 */
public record InviteUserResponse(
    String id,
    String username,
    String email,
    String role,
    List<String> roles,
    String temporaryPassword) {}
