package id.co.nativeapp.servicetemplate;

import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
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
   * All widgets visible to the bound tenant. Deliberately unfiltered in code: the result set is
   * constrained solely by the auto-applied RLS policy.
   */
  @Transactional(readOnly = true)
  public List<Widget> findAll() {
    return repository.findAll();
  }

  /** Count of widgets visible to the bound tenant (RLS-constrained). */
  @Transactional(readOnly = true)
  public long count() {
    return repository.count();
  }
}
