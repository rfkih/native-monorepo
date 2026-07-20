package id.co.nativeapp.security;

import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized list of <strong>tenant-optional (bootstrap) endpoints</strong> and <strong>fully
 * public (unauthenticated) endpoints</strong>, both bound under {@code native.security}.
 *
 * <p><strong>Tenant-optional ({@code native.security.tenant-optional}).</strong> The narrow set of
 * authenticated endpoints on which a valid token that carries no {@code company_id} claim must NOT
 * be rejected {@code 403} by {@link TenantBindingFilter}, because they CREATE the tenant rather
 * than operate within one (ENGINEERING-STANDARDS §7, externalized config). The motivating case is
 * the org-service tenant bootstrap: {@code POST /api/v1/companies}. Authentication is still
 * required; only the missing-tenant {@code 403} is waived.
 *
 * <p><strong>Public paths ({@code native.security.public-paths}).</strong> Fully unauthenticated
 * endpoints that must be reachable with NO bearer token — for example, the public sign-up endpoint
 * {@code POST /api/v1/signup}. These are {@code permitAll} in the Spring Security filter chain AND
 * skipped by {@link TenantBindingFilter} (which runs for all requests and would otherwise see no
 * JWT and emit {@code 401}). The default is EMPTY so no service exposes anything public unless it
 * explicitly opts in.
 *
 * <p>Each entry in either list is a {@code "METHOD /ant-path"} pair — an HTTP method, a single
 * space, then a path (e.g. {@code "POST /api/v1/signup"}). The {@code @Pattern} rejects a malformed
 * entry at startup (fail fast).
 */
@Validated
@ConfigurationProperties("native.security")
public class TenantOptionalProperties {

  /** A {@code "METHOD /ant-path"} entry: a token (the method), one space, then a {@code /}-path. */
  private static final String ENTRY_REGEX = "^[A-Za-z]+\\s+/\\S*$";

  private List<@Pattern(regexp = ENTRY_REGEX, message = "must be 'METHOD /ant-path'") String>
      tenantOptional = List.of();

  private List<@Pattern(regexp = ENTRY_REGEX, message = "must be 'METHOD /ant-path'") String>
      publicPaths = List.of();

  public List<String> getTenantOptional() {
    return tenantOptional;
  }

  public void setTenantOptional(List<String> tenantOptional) {
    this.tenantOptional = (tenantOptional == null) ? List.of() : List.copyOf(tenantOptional);
  }

  public List<String> getPublicPaths() {
    return publicPaths;
  }

  public void setPublicPaths(List<String> publicPaths) {
    this.publicPaths = (publicPaths == null) ? List.of() : List.copyOf(publicPaths);
  }

  /**
   * Compiles the configured {@code "METHOD /ant-path"} tenant-optional entries into {@link
   * RequestMatcher}s the {@link TenantBindingFilter} tests each request against.
   *
   * @return one matcher per configured entry (empty if none are configured)
   */
  public List<RequestMatcher> toRequestMatchers() {
    return tenantOptional.stream().map(TenantOptionalProperties::toMatcher).toList();
  }

  /**
   * Compiles the configured {@code "METHOD /ant-path"} public-path entries into {@link
   * RequestMatcher}s. Used by {@link JwtSecurityConfig} to {@code permitAll} these paths AND by
   * {@link TenantBindingFilter#shouldNotFilter} so token-less public requests are not intercepted.
   *
   * @return one matcher per configured entry (empty if none are configured)
   */
  public List<RequestMatcher> publicPathMatchers() {
    return publicPaths.stream().map(TenantOptionalProperties::toMatcher).toList();
  }

  static RequestMatcher toMatcher(String entry) {
    String[] parts = entry.strip().split("\\s+", 2);
    if (parts.length != 2) {
      // @Pattern validation already guarantees the two-part shape; this guards a programmatic
      // misuse (binding bypassed) so it fails loudly rather than producing a wrong matcher.
      throw new IllegalArgumentException(
          "security path entry must be 'METHOD /ant-path': " + entry);
    }
    HttpMethod method = HttpMethod.valueOf(parts[0].strip().toUpperCase(java.util.Locale.ROOT));
    return PathPatternRequestMatcher.pathPattern(method, parts[1].strip());
  }
}
