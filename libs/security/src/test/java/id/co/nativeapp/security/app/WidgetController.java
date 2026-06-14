package id.co.nativeapp.security.app;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A secured read endpoint under {@code /api/v1/**}. In the non-dev profile {@code libs/security}'s
 * {@code SecurityFilterChain} requires a valid JWT to reach it, and {@code TenantBindingFilter} has
 * already bound the tenant from the token's {@code company_id} claim, so the RLS-scoped read
 * returns only that tenant's widgets.
 *
 * <p>It also counts invocations so a test can prove a rejected (401/403) request NEVER reaches the
 * handler — the request is stopped at the security edge, not inside the controller.
 */
@RestController
public class WidgetController {

  /** Incremented on every handler invocation; a 401/403 request must leave this untouched. */
  public static final AtomicInteger INVOCATIONS = new AtomicInteger();

  private final WidgetReader reader;

  public WidgetController(WidgetReader reader) {
    this.reader = reader;
  }

  @GetMapping("/api/v1/widgets")
  public List<String> widgets() {
    INVOCATIONS.incrementAndGet();
    return reader.namesForCurrentTenant();
  }
}
