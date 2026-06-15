package id.co.nativeapp.finance.withinclose.repository;

import id.co.nativeapp.finance.withinclose.domain.WithinCompanyClose;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link WithinCompanyClose} aggregate.
 *
 * <p>A thin data port: no manual {@code WHERE company_id} — RLS scopes every read/write to the
 * bound company (rule 5). {@link #findByPeriod(String)} is the idempotency probe: a close that
 * already has a row for the period is a clean no-op (it re-emits nothing).
 */
public interface WithinCompanyCloseRepository extends JpaRepository<WithinCompanyClose, UUID> {

  /** The close row for a period within the bound company, if it has already closed. */
  Optional<WithinCompanyClose> findByPeriod(String period);
}
