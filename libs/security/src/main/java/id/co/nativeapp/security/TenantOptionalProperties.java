package id.co.nativeapp.security;

import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized list of <strong>tenant-optional (bootstrap) endpoints</strong> — the narrow set of
 * authenticated endpoints on which a valid token that carries no {@code company_id} claim must NOT
 * be rejected {@code 403} by {@link TenantBindingFilter}, because they CREATE the tenant rather
 * than operate within one (ENGINEERING-STANDARDS §7, externalized config).
 *
 * <p>The motivating case is the org-service tenant bootstrap: {@code POST /api/v1/companies} is the
 * owner creating their FIRST company, so the inbound token has no tenant yet. The endpoint is still
 * authenticated (the {@code SecurityFilterChain} rejects a missing/invalid/expired token with
 * {@code 401}); it is only the missing-{@code company_id} {@code 403} that is waived, and only for
 * the endpoints listed here. Every other {@code /api/v1/**} request keeps the strict behaviour: a
 * valid token with no tenant claim is a {@code 403} authZ denial.
 *
 * <p>Each entry is a {@code "METHOD /ant-path"} pair — an HTTP method, a single space, then an Ant
 * path (e.g. {@code "POST /api/v1/companies"}). The {@code @Pattern} rejects a malformed entry at
 * startup (fail fast) rather than silently widening or narrowing the exemption. The default is an
 * EMPTY list: a service opts an endpoint in explicitly via {@code native.security.tenant-optional},
 * so no endpoint is tenant-optional unless a service deliberately declares it — never the whole
 * API.
 */
@Validated
@ConfigurationProperties("native.security")
public class TenantOptionalProperties {

  /** A {@code "METHOD /ant-path"} entry: a token (the method), one space, then a {@code /}-path. */
  private static final String ENTRY_REGEX = "^[A-Za-z]+\\s+/\\S*$";

  private List<@Pattern(regexp = ENTRY_REGEX, message = "must be 'METHOD /ant-path'") String>
      tenantOptional = List.of();

  public List<String> getTenantOptional() {
    return tenantOptional;
  }

  public void setTenantOptional(List<String> tenantOptional) {
    this.tenantOptional = (tenantOptional == null) ? List.of() : List.copyOf(tenantOptional);
  }

  /**
   * Compiles the configured {@code "METHOD /ant-path"} entries into {@link RequestMatcher}s the
   * {@link TenantBindingFilter} tests each request against. Method + path are matched together (via
   * {@link PathPatternRequestMatcher}), so {@code "POST /api/v1/companies"} matches only that
   * method on exactly that path — a {@code GET}, or a sub-resource such as {@code
   * /api/v1/companies/{id}/businesses}, does NOT match and stays strictly tenant-scoped.
   *
   * @return one matcher per configured entry (empty if none are configured)
   */
  public List<RequestMatcher> toRequestMatchers() {
    return tenantOptional.stream().map(TenantOptionalProperties::toMatcher).toList();
  }

  private static RequestMatcher toMatcher(String entry) {
    String[] parts = entry.strip().split("\\s+", 2);
    if (parts.length != 2) {
      // @Pattern validation already guarantees the two-part shape; this guards a programmatic
      // misuse (binding bypassed) so it fails loudly rather than producing a wrong matcher.
      throw new IllegalArgumentException(
          "tenant-optional entry must be 'METHOD /ant-path': " + entry);
    }
    HttpMethod method = HttpMethod.valueOf(parts[0].strip().toUpperCase(java.util.Locale.ROOT));
    return PathPatternRequestMatcher.pathPattern(method, parts[1].strip());
  }
}
