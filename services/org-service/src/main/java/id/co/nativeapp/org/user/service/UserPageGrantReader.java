package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.user.domain.UserPageGrant;
import id.co.nativeapp.org.user.repository.UserPageGrantRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for user page grants — a {@code *Reader} {@code @Component} (org-service convention:
 * readers are {@code @Component}, not {@code @Service}) so the proxy + auto-RLS aspect engage. RLS
 * scopes every read to the bound tenant (rule 5).
 */
@Component
public class UserPageGrantReader {

  private final UserPageGrantRepository repository;

  public UserPageGrantReader(UserPageGrantRepository repository) {
    this.repository = repository;
  }

  /** The active page keys granted to a user (empty = the full role surface — grandfather). */
  @Transactional(readOnly = true)
  public List<String> activePageKeys(String userId) {
    return repository.findByUserIdAndActiveTrue(userId).stream()
        .map(UserPageGrant::getPageKey)
        .sorted()
        .toList();
  }
}
