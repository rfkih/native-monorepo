package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.user.config.KeycloakAdminProperties;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin HTTP client over the Keycloak Admin REST API, scoped to the operations needed for sign-up:
 * check if a user exists, create a user, and assign a realm role.
 *
 * <p>Uses client-credentials (service-account) authentication. The access token is cached until
 * shortly before expiry to avoid a round-trip on every sign-up call.
 *
 * <p><strong>Credential + PII hygiene (rule 6 / HR-6).</strong> The client secret is NEVER logged.
 * The caller's {@code ownerPassword} is never passed to this class's logging paths; this class does
 * not see or log it.
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
