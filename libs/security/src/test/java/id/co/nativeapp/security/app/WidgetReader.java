package id.co.nativeapp.security.app;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads widgets inside a {@code @Transactional} unit of work so the auto-RLS aspect sets {@code
 * app.current_tenant} on the connection from the bound {@link
 * id.co.nativeapp.tenant.TenantContext}. The query is therefore implicitly scoped to the tenant
 * bound from the validated JWT.
 */
@Service
public class WidgetReader {

  private final WidgetRepository repository;

  public WidgetReader(WidgetRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public List<String> namesForCurrentTenant() {
    return repository.findAll().stream().map(Widget::getName).toList();
  }
}
