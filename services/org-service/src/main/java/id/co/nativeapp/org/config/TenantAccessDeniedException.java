package id.co.nativeapp.org.config;

/**
 * Thrown when a request targets a tenant ({@code company_id}) that differs from the one bound to
 * the current scope — a confused-deputy attempt (e.g. a path {@code companyId} that does not match
 * the authenticated tenant). The {@link ApiExceptionHandler} maps it to a {@code 403 Forbidden} RFC
 * 7807 {@code ProblemDetail}; the request is rejected, never silently accepted against the bound
 * tenant.
 *
 * <p>It carries no caller-supplied tenant ids in its message (HR-6) — only a generic forbidden
 * statement — so nothing tenant-identifying leaks to the client.
 */
public class TenantAccessDeniedException extends RuntimeException {

  public TenantAccessDeniedException() {
    super("The requested company does not match the authenticated tenant.");
  }
}
