package id.co.nativeapp.servicetemplate.widget;

import id.co.nativeapp.servicetemplate.config.RlsAutoApplyAspect;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Widget}.
 *
 * <p>Deliberately carries <em>no</em> manual tenant filtering and no call to apply the tenant GUC:
 * every Spring Data method is transactional, so {@link RlsAutoApplyAspect} sets {@code
 * app.current_tenant} on the connection automatically and the PostgreSQL RLS policy restricts
 * results to the bound company. This is the point of the template — correctness by default, even
 * for the developer who forgets.
 */
public interface WidgetRepository extends JpaRepository<Widget, Long> {}
