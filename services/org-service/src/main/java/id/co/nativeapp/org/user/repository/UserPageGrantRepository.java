package id.co.nativeapp.org.user.repository;

import id.co.nativeapp.org.user.domain.UserPageGrant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data port for {@link UserPageGrant}. Derived queries only; no manual {@code WHERE
 * company_id} — RLS scopes every query to the bound tenant (rule 5).
 */
public interface UserPageGrantRepository extends JpaRepository<UserPageGrant, UUID> {

  /**
   * Every grant row for a user (active + inactive) — the replace-set path reopens inactive rows.
   */
  List<UserPageGrant> findByUserId(String userId);

  /** Active grant rows for a user — the "my pages" / editor read. */
  List<UserPageGrant> findByUserIdAndActiveTrue(String userId);
}
