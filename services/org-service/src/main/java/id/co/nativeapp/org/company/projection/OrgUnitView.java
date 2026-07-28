package id.co.nativeapp.org.company.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection over the {@code org_unit} row — only the columns an {@link
 * id.co.nativeapp.org.company.dto.OrgUnitResponse} needs, never the {@code Auditable} bookkeeping.
 *
 * <p>Backs the native read query on {@link
 * id.co.nativeapp.org.company.repository.OrgUnitRepository} ({@code findAllViews}). Snake_case
 * native-query aliases map to these accessors via Spring Data's projection-interface convention
 * (CLAUDE.md "native-query aliases snake_case; map via projection interfaces"), so a read path
 * fetches a narrow column set instead of {@code SELECT *} of the full entity. Lives in its own
 * {@code projection} package — a read model is neither the write-side {@code domain} entity nor a
 * request/response {@code dto}.
 *
 * <p>The write path ({@link id.co.nativeapp.org.company.service.OrgUnitWriter#cascadeDeactivate}
 * calling {@code orgUnitRepository.findAll()}) still uses the full {@code @Entity} path because the
 * loaded nodes are mutated ({@code deactivate()}) and saved — inherited JpaRepository CRUD, not
 * projection-ized (rule 3).
 */
public interface OrgUnitView {

  UUID getId();

  String getName();

  /** The org-unit type string (e.g. {@code "OUTLET"}), as stored in the {@code type} column. */
  String getType();

  /**
   * The business vertical (LOWERCASE module-key string, e.g. {@code "restaurant"}) — non-null only
   * for {@code BUSINESS_UNIT} rows. Note the deliberate casing divergence from {@link #getType()}.
   */
  String getVertical();

  UUID getParentId();

  UUID getLegalEmployerId();

  /**
   * The owning tenant ({@code company_id}), as a string. The column is {@code VARCHAR(64)} storing
   * a UUID text; callers convert via {@code UUID.fromString(view.getCompanyId())}.
   */
  String getCompanyId();

  boolean isActive();

  LocalDate getEffectiveFrom();

  LocalDate getEffectiveTo();
}
