package id.co.nativeapp.security.app;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Widget}. There is deliberately NO {@code ...ByCompanyId...}
 * method and no manual tenant predicate: tenant scoping comes solely from the auto-applied RLS GUC
 * (CLAUDE.md rule 5). {@code findAll()} therefore returns only the bound tenant's rows.
 */
public interface WidgetRepository extends JpaRepository<Widget, UUID> {
  @Override
  List<Widget> findAll();
}
