package id.co.nativeapp.employee.org.service;

import id.co.nativeapp.employee.org.dto.OrgUnitLookupResponse;
import id.co.nativeapp.employee.org.repository.OrgUnitProjectionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the local org read model for the console lookup ({@code GET /api/v1/employees/org-units}) —
 * the query side. A {@code @Transactional(readOnly = true)} service bean so the proxy + auto-RLS
 * aspect engage: the query carries NO {@code WHERE company_id}; ids the bound tenant cannot see are
 * silently absent (rule 5).
 */
@Service
public class OrgUnitLookupReader {

  /** The maximum lookup size — one IN chunk, well under the ≤1000 rule. */
  static final int MAX_IDS = 200;

  private final OrgUnitProjectionRepository repository;

  public OrgUnitLookupReader(OrgUnitProjectionRepository repository) {
    this.repository = repository;
  }

  /**
   * The lookup rows for the requested org-unit ids (RLS-scoped; unknown/foreign ids are absent).
   *
   * @throws IllegalArgumentException on an empty or oversized id list (→ 400)
   */
  @Transactional(readOnly = true)
  public List<OrgUnitLookupResponse> lookup(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      throw new IllegalArgumentException("ids must contain at least one org-unit id");
    }
    if (ids.size() > MAX_IDS) {
      throw new IllegalArgumentException("ids accepts at most " + MAX_IDS + " org-unit ids");
    }
    // Projection-to-DTO mapping happens here in the service layer (CODE-STRUCTURE §3.3).
    return repository.findViewsByIds(ids).stream()
        .map(
            v ->
                new OrgUnitLookupResponse(
                    v.getOrgUnitId(),
                    v.getLegalEmployerId(),
                    v.getType(),
                    Boolean.TRUE.equals(v.getActive())))
        .toList();
  }
}
