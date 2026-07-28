package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.user.domain.UserPageGrant;
import id.co.nativeapp.org.user.repository.UserPageGrantRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} replace-set write for user page grants — a distinct {@code
 * *Writer} bean so the Spring proxy + {@link RlsAutoApplyAspect} set the tenant GUC (rule 5). No
 * outbox/event: page grants are console-only (deliberate — see the ADR), so there is nothing to
 * publish.
 *
 * <p>Replace-set semantics (mirrors {@code UserOutletAssignmentWriter} minus events/effective
 * dating): pages in the new set but not active → insert or reopen; pages active but not in the new
 * set → deactivate; pages in both → no-op. The page-key whitelist is validated in the service
 * before this runs.
 */
@Component
public class UserPageGrantWriter {

  private final UserPageGrantRepository repository;

  public UserPageGrantWriter(UserPageGrantRepository repository) {
    this.repository = repository;
  }

  /** Atomically replaces the page-grant set for a user with {@code desiredPageKeys}. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void replaceGrants(String userId, List<String> desiredPageKeys) {
    String tenant = TenantContext.require().companyId();
    Set<String> desired = new HashSet<>(desiredPageKeys);

    List<UserPageGrant> existing = repository.findByUserId(userId);
    Map<String, UserPageGrant> byPage =
        existing.stream().collect(Collectors.toMap(UserPageGrant::getPageKey, g -> g));

    // Deactivate active grants no longer desired.
    for (UserPageGrant grant : existing) {
      if (grant.isActive() && !desired.contains(grant.getPageKey())) {
        grant.deactivate();
        repository.save(grant);
      }
    }

    // Insert or reopen each desired grant.
    for (String pageKey : desired) {
      UserPageGrant grant = byPage.get(pageKey);
      if (grant == null) {
        UserPageGrant created = new UserPageGrant(userId, pageKey);
        created.setCompanyId(tenant);
        repository.save(created);
      } else if (!grant.isActive()) {
        grant.reactivate();
        repository.save(grant);
      }
    }

    repository.flush();
  }
}
