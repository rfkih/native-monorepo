package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.user.config.KeycloakAdminProperties;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin HTTP client over the Keycloak Admin REST API, covering user sign-up and team management
 * operations.
 *
 * <p>Uses client-credentials (service-account) authentication. The access token is cached until
 * shortly before expiry to avoid a round-trip on every call.
 *
 * <p><strong>Credential + PII hygiene (rule 6 / HR-6).</strong> The client secret is NEVER logged.
 * Passwords (both the owner's initial password and generated temporary passwords) are never logged
 * anywhere in this class — they are passed directly to the Keycloak API without assignment to any
 * variable that flows to a log statement. The caller's email address is also never logged.
 *
 * <p>Any non-2xx or I/O error that is not explicitly handled is mapped to a {@link
 * KeycloakAdminException} (unchecked) so callers need not manage checked exceptions.
 *
 * <p>This is an infrastructure edge to the identity provider, not a business-to-business service
 * call — mirroring the JWKS fetch that every service makes against Keycloak. Hard rule 2 (no
 * synchronous business service calls) therefore holds.
 *
 * <p>The bean is registered by {@link id.co.nativeapp.org.user.config.KeycloakAdminConfig} (which
 * performs the startup timeout self-check before construction) — NOT via {@code @Component}.
 */
public class KeycloakAdminClient {

  private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

  /**
   * Expire the cached token this many seconds before its real expiry to avoid using a stale one.
   */
  private static final int TOKEN_EXPIRY_BUFFER_SECONDS = 30;

  /**
   * Max users returned in a single list call. Keycloak's default max is 100; we use 500 to support
   * teams of realistic sizes without pagination in this slice. An N+1 role lookup is acceptable for
   * expected small team sizes (< 50 users per company).
   */
  private static final int MAX_USERS = 500;

  /** Characters used for generated temporary passwords: alphanumeric + common safe symbols. */
  private static final String PASSWORD_CHARS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";

  private static final int TEMP_PASSWORD_LENGTH = 16;

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final KeycloakAdminProperties props;
  private final RestClient restClient;

  /** Cached admin access token and the instant it expires (minus the buffer). */
  private volatile String cachedToken;

  private volatile Instant tokenExpiresAt = Instant.EPOCH;

  public KeycloakAdminClient(KeycloakAdminProperties props) {
    this.props = props;

    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(props.getConnectTimeout());
    factory.setReadTimeout(props.getReadTimeout());

    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  /**
   * Returns {@code true} if a Keycloak user with the given email already exists in the realm (exact
   * match on the email attribute).
   *
   * @throws KeycloakAdminException if the Admin API is unreachable or returns an unexpected error
   */
  public boolean usernameOrEmailExists(String email) {
    String token = acquireToken();
    String url =
        props.getBaseUrl()
            + "/admin/realms/"
            + props.getRealm()
            + "/users?email="
            + encode(email)
            + "&exact=true";
    try {
      ResponseEntity<List> response =
          restClient
              .get()
              .uri(URI.create(url))
              .header("Authorization", "Bearer " + token)
              .retrieve()
              .toEntity(List.class);
      List<?> users = response.getBody();
      return users != null && !users.isEmpty();
    } catch (RestClientResponseException e) {
      throw new KeycloakAdminException(
          "Keycloak user lookup failed with status " + e.getStatusCode(), e);
    } catch (RestClientException e) {
      throw new KeycloakAdminException("Keycloak user lookup failed — connection error", e);
    }
  }

  /**
   * Creates a Keycloak user with the given email/username, sets their initial password, tags them
   * with the {@code company_id} user attribute, and returns the new user's Keycloak id (UUID).
   *
   * <p><strong>The password parameter is NEVER logged.</strong>
   *
   * @param email the user's email (also used as username)
   * @param password the initial password — NEVER logged
   * @param companyId the new tenant's company id, set as the {@code company_id} user attribute so
   *     Keycloak maps it into the JWT claim
   * @return the Keycloak user id (UUID string) extracted from the {@code Location} response header
   * @throws KeycloakAdminException if the Admin API is unreachable or user creation fails
   */
  public String createUser(String email, String password, String companyId) {
    String token = acquireToken();
    String url = props.getBaseUrl() + "/admin/realms/" + props.getRealm() + "/users";

    Map<String, Object> body =
        Map.of(
            "username",
            email,
            "email",
            email,
            "enabled",
            true,
            "attributes",
            Map.of("company_id", List.of(companyId)),
            "credentials",
            List.of(Map.of("type", "password", "value", password, "temporary", false)));

    try {
      ResponseEntity<Void> response =
          restClient
              .post()
              .uri(URI.create(url))
              .header("Authorization", "Bearer " + token)
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .toBodilessEntity();

      URI location = response.getHeaders().getLocation();
      if (location == null) {
        throw new KeycloakAdminException(
            "Keycloak user creation succeeded but returned no Location header");
      }
      // Location is e.g. http://host/admin/realms/native/users/{userId}
      String path = location.getPath();
      return path.substring(path.lastIndexOf('/') + 1);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.CONFLICT) {
        // 409 from the Admin API means the user already exists despite our pre-check; treat as
        // email-already-exists rather than a 502.
        throw new EmailAlreadyExistsException(email);
      }
      throw new KeycloakAdminException(
          "Keycloak user creation failed with status " + e.getStatusCode(), e);
    } catch (RestClientException e) {
      throw new KeycloakAdminException("Keycloak user creation failed — connection error", e);
    }
  }

  /**
   * Assigns the named realm role to the Keycloak user identified by {@code userId}.
   *
   * @param userId the Keycloak user id (UUID string returned from {@link #createUser})
   * @param roleName the realm role to assign (e.g. {@code "owner"})
   * @throws KeycloakAdminException if the role does not exist or the assignment fails
   */
  public void assignRealmRole(String userId, String roleName) {
    String token = acquireToken();
    String realmBase = props.getBaseUrl() + "/admin/realms/" + props.getRealm();

    // 1. Fetch the role representation to get its id.
    String roleUrl = realmBase + "/roles/" + encode(roleName);
    Map<?, ?> roleRep;
    try {
      roleRep =
          restClient
              .get()
              .uri(URI.create(roleUrl))
              .header("Authorization", "Bearer " + token)
              .retrieve()
              .body(Map.class);
    } catch (RestClientResponseException e) {
      throw new KeycloakAdminException(
          "Keycloak role lookup for '" + roleName + "' failed with status " + e.getStatusCode(), e);
    } catch (RestClientException e) {
      throw new KeycloakAdminException(
          "Keycloak role lookup for '" + roleName + "' failed — connection error", e);
    }
    if (roleRep == null) {
      throw new KeycloakAdminException(
          "Keycloak returned null role representation for " + roleName);
    }

    // 2. POST the role mapping.
    String mappingUrl = realmBase + "/users/" + userId + "/role-mappings/realm";
    try {
      restClient
          .post()
          .uri(URI.create(mappingUrl))
          .header("Authorization", "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON)
          .body(List.of(roleRep))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      throw new KeycloakAdminException(
          "Keycloak role assignment for '" + roleName + "' failed with status " + e.getStatusCode(),
          e);
    } catch (RestClientException e) {
      throw new KeycloakAdminException(
          "Keycloak role assignment for '" + roleName + "' failed — connection error", e);
    }

    log.info("Assigned realm role '{}' to Keycloak user {}", roleName, userId);
  }

  /**
   * Lists all users in the realm whose {@code company_id} attribute matches {@code companyId}.
   *
   * <p>Uses Keycloak's attribute search ({@code q=company_id:{value}}) to filter at the server
   * side. For each user, a second call fetches the user's realm role mappings to populate the
   * {@code roles} field. This N+1 pattern is acceptable given expected small team sizes.
   *
   * @param companyId the tenant's company id to filter by
   * @return list of users belonging to the company; empty if none match
   * @throws KeycloakAdminException if the Admin API is unreachable or returns an unexpected error
   */
  @SuppressWarnings("unchecked")
  public List<KeycloakUser> listUsersByCompanyId(String companyId) {
    String token = acquireToken();
    String url =
        props.getBaseUrl()
            + "/admin/realms/"
            + props.getRealm()
            + "/users?q=company_id:"
            + encode(companyId)
            + "&max="
            + MAX_USERS;
    List<Map<?, ?>> rawUsers;
    try {
      rawUsers =
          restClient
              .get()
              .uri(URI.create(url))
              .header("Authorization", "Bearer " + token)
              .retrieve()
              .body(List.class);
    } catch (RestClientResponseException e) {
      throw new KeycloakAdminException(
          "Keycloak user list failed with status " + e.getStatusCode(), e);
    } catch (RestClientException e) {
      throw new KeycloakAdminException("Keycloak user list failed — connection error", e);
    }

    if (rawUsers == null) {
      return List.of();
    }

    List<KeycloakUser> result = new ArrayList<>(rawUsers.size());
    for (Map<?, ?> raw : rawUsers) {
      String userId = (String) raw.get("id");
      List<String> roles = fetchBusinessRoles(userId, token);
      result.add(mapToKeycloakUser(raw, roles));
    }
    return result;
  }

  /**
   * Fetches a single Keycloak user by id, including their {@code company_id} attribute and business
   * realm roles. Returns empty if the user does not exist (Keycloak 404).
   *
   * @param userId the Keycloak user UUID
   * @return the user, or empty if not found
   * @throws KeycloakAdminException if the Admin API returns an unexpected error
   */
  @SuppressWarnings("unchecked")
  public Optional<KeycloakUser> getUserById(String userId) {
    String token = acquireToken();
    String url = props.getBaseUrl() + "/admin/realms/" + props.getRealm() + "/users/" + userId;
    Map<?, ?> raw;
    try {
      raw =
          restClient
              .get()
              .uri(URI.create(url))
              .header("Authorization", "Bearer " + token)
              .retrieve()
              .body(Map.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        return Optional.empty();
      }
      throw new KeycloakAdminException(
          "Keycloak get-user failed with status " + e.getStatusCode(), e);
    } catch (RestClientException e) {
      throw new KeycloakAdminException("Keycloak get-user failed — connection error", e);
    }

    if (raw == null) {
      return Optional.empty();
    }
    List<String> roles = fetchBusinessRoles(userId, token);
    return Optional.of(mapToKeycloakUser(raw, roles));
  }

  /**
   * Result of creating an invited user: the new user's Keycloak id and the generated temporary
   * password. The password is returned ONCE and NEVER stored or logged.
   */
  public record InviteResult(String userId, String temporaryPassword) {}

  /**
   * Creates a Keycloak user for an invited teammate. The user is created with a server-generated
   * temporary password and the {@code UPDATE_PASSWORD} required action, so they must change it on
   * first login.
   *
   * <p><strong>The generated password is NEVER logged.</strong>
   *
   * @param email the new user's email (also used as username)
   * @param companyId the tenant company id to set as the {@code company_id} user attribute
   * @param role the initial realm role to assign (must be one of owner/manager/cashier)
   * @return the {@link InviteResult} containing the new Keycloak user id and the one-time temporary
   *     password — NEVER log or store the password
   * @throws EmailAlreadyExistsException if a Keycloak account already exists for this email
   * @throws KeycloakAdminException if the Admin API is unreachable or returns an unexpected error
   */
  public InviteResult createInvitedUser(String email, String companyId, String role) {
    // Generate a secure temporary password. It is never assigned to any variable that touches a
    // log statement — the char[] is converted to String and stored only in the returned record.
    String tempPassword = generateTemporaryPassword();

    String token = acquireToken();
    String url = props.getBaseUrl() + "/admin/realms/" + props.getRealm() + "/users";

    Map<String, Object> body =
        Map.of(
            "username",
            email,
            "email",
            email,
            "enabled",
            true,
            "attributes",
            Map.of("company_id", List.of(companyId)),
            "requiredActions",
            List.of("UPDATE_PASSWORD"),
            "credentials",
            List.of(Map.of("type", "password", "value", tempPassword, "temporary", true)));

    try {
      ResponseEntity<Void> response =
          restClient
              .post()
              .uri(URI.create(url))
              .header("Authorization", "Bearer " + token)
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .toBodilessEntity();

      URI location = response.getHeaders().getLocation();
      if (location == null) {
        throw new KeycloakAdminException(
            "Keycloak user creation succeeded but returned no Location header");
      }
      String path = location.getPath();
      String userId = path.substring(path.lastIndexOf('/') + 1);

      assignRealmRole(userId, role);
      log.info("Invited user created in Keycloak: userId={}", userId);
      return new InviteResult(userId, tempPassword);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.CONFLICT) {
        throw new EmailAlreadyExistsException(email);
      }
      throw new KeycloakAdminException(
          "Keycloak invited-user creation failed with status " + e.getStatusCode(), e);
    } catch (RestClientException e) {
      throw new KeycloakAdminException(
          "Keycloak invited-user creation failed — connection error", e);
    }
  }

  /**
   * Enables or disables the Keycloak user identified by {@code userId}.
   *
   * @param userId the Keycloak user UUID
   * @param enabled {@code true} to enable, {@code false} to disable
   * @throws KeycloakAdminException if the Admin API is unreachable or returns an unexpected error
   */
  public void setEnabled(String userId, boolean enabled) {
    String token = acquireToken();
    String url = props.getBaseUrl() + "/admin/realms/" + props.getRealm() + "/users/" + userId;
    try {
      restClient
          .put()
          .uri(URI.create(url))
          .header("Authorization", "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("enabled", enabled))
          .retrieve()
          .toBodilessEntity();
      log.info("Set Keycloak user {} enabled={}", userId, enabled);
    } catch (RestClientResponseException e) {
      throw new KeycloakAdminException(
          "Keycloak set-enabled for user " + userId + " failed with status " + e.getStatusCode(),
          e);
    } catch (RestClientException e) {
      throw new KeycloakAdminException(
          "Keycloak set-enabled for user " + userId + " failed — connection error", e);
    }
  }

  /**
   * Replaces the user's current business realm roles (owner/manager/cashier) with {@code newRole}.
   *
   * <p>The implementation: (1) fetches the current realm roles to find which business roles the
   * user holds; (2) removes those roles; (3) assigns the new role. Only the business roles ({@code
   * owner}/{@code manager}/{@code cashier}) are touched — Keycloak's built-in default roles are NOT
   * stripped.
   *
   * @param userId the Keycloak user UUID
   * @param newRole the new business role to assign (must be one of owner/manager/cashier)
   * @throws KeycloakAdminException if the Admin API is unreachable or returns an unexpected error
   */
  @SuppressWarnings("unchecked")
  public void replaceRealmRole(String userId, String newRole) {
    String token = acquireToken();
    String realmBase = props.getBaseUrl() + "/admin/realms/" + props.getRealm();
    String mappingUrl = realmBase + "/users/" + userId + "/role-mappings/realm";

    // 1. Fetch existing realm role mappings.
    List<Map<?, ?>> existingMappings;
    try {
      existingMappings =
          restClient
              .get()
              .uri(URI.create(mappingUrl))
              .header("Authorization", "Bearer " + token)
              .retrieve()
              .body(List.class);
    } catch (RestClientResponseException e) {
      throw new KeycloakAdminException(
          "Keycloak role-mapping fetch for user "
              + userId
              + " failed with status "
              + e.getStatusCode(),
          e);
    } catch (RestClientException e) {
      throw new KeycloakAdminException(
          "Keycloak role-mapping fetch for user " + userId + " failed — connection error", e);
    }

    // 2. Filter down to only the business roles (to avoid stripping default Keycloak roles).
    List<Map<?, ?>> businessRolesToRemove = new ArrayList<>();
    if (existingMappings != null) {
      for (Map<?, ?> roleRep : existingMappings) {
        String roleName = (String) roleRep.get("name");
        if (KeycloakUser.BUSINESS_ROLES.contains(roleName)) {
          businessRolesToRemove.add(roleRep);
        }
      }
    }

    // 3. Remove the current business roles (DELETE with body).
    if (!businessRolesToRemove.isEmpty()) {
      try {
        restClient
            .method(HttpMethod.DELETE)
            .uri(URI.create(mappingUrl))
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(businessRolesToRemove)
            .retrieve()
            .toBodilessEntity();
      } catch (RestClientResponseException e) {
        throw new KeycloakAdminException(
            "Keycloak role removal for user " + userId + " failed with status " + e.getStatusCode(),
            e);
      } catch (RestClientException e) {
        throw new KeycloakAdminException(
            "Keycloak role removal for user " + userId + " failed — connection error", e);
      }
    }

    // 4. Assign the new role.
    assignRealmRole(userId, newRole);
    log.info("Replaced business role for Keycloak user {} with '{}'", userId, newRole);
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /**
   * Fetches the business realm roles (owner/manager/cashier) for the given user. Returns only the
   * names that are in the business roles set, ignoring built-in Keycloak roles.
   */
  @SuppressWarnings("unchecked")
  private List<String> fetchBusinessRoles(String userId, String token) {
    String url =
        props.getBaseUrl()
            + "/admin/realms/"
            + props.getRealm()
            + "/users/"
            + userId
            + "/role-mappings/realm";
    List<Map<?, ?>> mappings;
    try {
      mappings =
          restClient
              .get()
              .uri(URI.create(url))
              .header("Authorization", "Bearer " + token)
              .retrieve()
              .body(List.class);
    } catch (RestClientResponseException e) {
      throw new KeycloakAdminException(
          "Keycloak role-mapping fetch for user "
              + userId
              + " failed with status "
              + e.getStatusCode(),
          e);
    } catch (RestClientException e) {
      throw new KeycloakAdminException(
          "Keycloak role-mapping fetch for user " + userId + " failed — connection error", e);
    }

    if (mappings == null) {
      return List.of();
    }
    List<String> roles = new ArrayList<>();
    for (Map<?, ?> roleRep : mappings) {
      String name = (String) roleRep.get("name");
      if (name != null && KeycloakUser.BUSINESS_ROLES.contains(name)) {
        roles.add(name);
      }
    }
    return roles;
  }

  /**
   * Maps a raw Keycloak user representation (a JSON-decoded Map) to a {@link KeycloakUser}.
   * Extracts the {@code company_id} attribute from the nested {@code attributes} map.
   */
  @SuppressWarnings("unchecked")
  private static KeycloakUser mapToKeycloakUser(Map<?, ?> raw, List<String> roles) {
    String id = (String) raw.get("id");
    String username = (String) raw.get("username");
    String email = (String) raw.get("email");
    Boolean enabled = (Boolean) raw.get("enabled");

    String companyId = null;
    Object attrs = raw.get("attributes");
    if (attrs instanceof Map<?, ?> attrMap) {
      Object companyIdAttr = attrMap.get("company_id");
      if (companyIdAttr instanceof List<?> companyIdList && !companyIdList.isEmpty()) {
        companyId = (String) companyIdList.getFirst();
      }
    }

    return new KeycloakUser(id, username, email, Boolean.TRUE.equals(enabled), companyId, roles);
  }

  /**
   * Generates a cryptographically random temporary password of length {@link #TEMP_PASSWORD_LENGTH}
   * using characters from {@link #PASSWORD_CHARS}.
   *
   * <p><strong>NEVER log the returned value.</strong>
   */
  private static String generateTemporaryPassword() {
    char[] chars = PASSWORD_CHARS.toCharArray();
    char[] password = new char[TEMP_PASSWORD_LENGTH];
    for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
      password[i] = chars[SECURE_RANDOM.nextInt(chars.length)];
    }
    return new String(password);
  }

  /**
   * Obtains (or returns the cached) admin access token via the client-credentials grant. The token
   * is cached until {@link #TOKEN_EXPIRY_BUFFER_SECONDS} seconds before its actual expiry.
   *
   * <p><strong>The client secret is NEVER logged.</strong>
   *
   * @throws KeycloakAdminException if the token endpoint is unreachable or returns an error
   */
  @SuppressWarnings("unchecked")
  private synchronized String acquireToken() {
    if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
      return cachedToken;
    }

    String tokenUrl =
        props.getBaseUrl() + "/realms/" + props.getRealm() + "/protocol/openid-connect/token";

    // Build URL-encoded form body without leaking the secret into a log-visible string.
    String formBody =
        "grant_type=client_credentials"
            + "&client_id="
            + encode(props.getClientId())
            + "&client_secret="
            + encode(props.getClientSecret());

    Map<String, Object> tokenResponse;
    try {
      tokenResponse =
          restClient
              .post()
              .uri(URI.create(tokenUrl))
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(formBody)
              .retrieve()
              .body(Map.class);
    } catch (RestClientResponseException e) {
      throw new KeycloakAdminException(
          "Keycloak token endpoint returned status " + e.getStatusCode());
    } catch (RestClientException e) {
      throw new KeycloakAdminException("Keycloak token endpoint unreachable — connection error", e);
    }

    if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
      throw new KeycloakAdminException("Keycloak token response missing access_token");
    }

    cachedToken = (String) tokenResponse.get("access_token");
    int expiresIn =
        tokenResponse.containsKey("expires_in")
            ? ((Number) tokenResponse.get("expires_in")).intValue()
            : 300;
    tokenExpiresAt =
        Instant.now().plusSeconds(Math.max(0, expiresIn - TOKEN_EXPIRY_BUFFER_SECONDS));

    log.debug("Acquired Keycloak admin token; expires in {}s", expiresIn);
    return cachedToken;
  }

  /** URL-encodes a query-parameter or form value. */
  private static String encode(String value) {
    try {
      return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      return value;
    }
  }
}
