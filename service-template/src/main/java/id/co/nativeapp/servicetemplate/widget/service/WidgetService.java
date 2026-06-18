package id.co.nativeapp.servicetemplate.widget.service;

import id.co.nativeapp.servicetemplate.widget.domain.Widget;
import id.co.nativeapp.servicetemplate.widget.projection.WidgetView;
import id.co.nativeapp.servicetemplate.widget.repository.WidgetRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin application service over {@link WidgetRepository}.
 *
 * <p>Every method is {@code @Transactional}, so {@link RlsAutoApplyAspect} sets the tenant GUC on
 * the connection automatically before the body runs — no method here applies the tenant by hand. On
 * create it stamps {@code company_id} from the bound {@link TenantContext} (the RLS {@code WITH
 * CHECK} then guarantees a row can only be written under the session tenant); reads carry no {@code
 * WHERE company_id = ...} at all and rely entirely on RLS for isolation, which is the behaviour the
 * negative test pins down.
 */
@Service
public class WidgetService {

  private final WidgetRepository repository;

  public WidgetService(WidgetRepository repository) {
    this.repository = repository;
  }

  /** Creates a widget under the bound tenant. */
  @Transactional
  public Widget create(String name) {
    Widget widget = new Widget(name);
    widget.setCompanyId(TenantContext.require().companyId());
    return repository.save(widget);
  }

  /**
   * All widgets visible to the bound tenant, as a {@link WidgetView} read projection (native query
   * — only the needed columns, never {@code SELECT *} of the entity; CLAUDE.md native-query
   * convention). Deliberately unfiltered in code: the result set is constrained solely by the
   * auto-applied RLS policy.
   */
  @Transactional(readOnly = true)
  public List<WidgetView> findAll() {
    return repository.findAllViews();
  }

  /**
   * The full {@link Widget} entity by id within the bound tenant (RLS-scoped), if any. Returns the
   * ENTITY rather than a projection — the write-path / full-row side of the convention: a caller
   * loads it to mutate+save, and it carries the {@code Auditable} columns a narrow read projection
   * omits.
   */
  @Transactional(readOnly = true)
  public Optional<Widget> findById(Long id) {
    return repository.findById(id);
  }

  /** Count of widgets visible to the bound tenant (RLS-constrained). */
  @Transactional(readOnly = true)
  public long count() {
    return repository.count();
  }
}
