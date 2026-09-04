package id.co.nativeapp.org.company.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.domain.OrgUnitChangeKind;
import id.co.nativeapp.org.company.domain.OrgUnitType;
import id.co.nativeapp.org.company.messaging.OrgUnitChangedSchema;
import id.co.nativeapp.org.company.messaging.OrgUnitDeletedSchema;
import id.co.nativeapp.org.company.repository.OrgUnitRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the ONE {@code @Transactional} unit of work that flattens a single tenant's org tree to the
 * ADR 0070 shape ({@code company > outlet}).
 *
 * <p>It is a distinct bean (not a private method on {@link OrgTreeFlatteningReconciler}) so the
 * method is invoked through the Spring proxy: a self-invocation would bypass the
 * {@code @Transactional} advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC,
 * without which every read comes back empty and every write fails the RLS {@code WITH CHECK} (rule
 * 5). The caller binds the tenant before invoking.
 *
 * <p><strong>Everything for one tenant commits together</strong> — the reparenting, the deletes,
 * and every outbox row (rule 3). A failure mid-tenant rolls the whole tenant back and leaves its
 * work row pending, so the next boot retries it cleanly; tenants are independent, so one bad tenant
 * never blocks the rest.
 *
 * <p><strong>Idempotent by nature, not by bookkeeping.</strong> The work is defined by what is
 * still wrong ({@code parent_id IS NOT NULL}, or a node that is not an {@code OUTLET}); a second
 * run over an already-flat tenant finds nothing to do and emits nothing. The work queue exists only
 * so the reconciler can DISCOVER tenants under FORCE RLS — not to make it safe to re-run.
 */
@Component
public class OrgTreeFlatteningWriter {

  /** The audit actor stamped into {@code updated_by} for rows this migration touches. */
  public static final String RECONCILER_ACTOR = "org-tree-flattening-reconciler";

  private final OrgUnitRepository orgUnitRepository;
  private final OutboxWriter outboxWriter;
  private final Clock clock;

  public OrgTreeFlatteningWriter(
      OrgUnitRepository orgUnitRepository, OutboxWriter outboxWriter, Clock clock) {
    this.orgUnitRepository = orgUnitRepository;
    this.outboxWriter = outboxWriter;
    this.clock = clock;
  }

  /**
   * Flattens the bound tenant's org tree: every outlet is lifted to the top level (one {@code
   * OrgUnitChanged}/{@code MOVED} each), then every node that is not an outlet — the retired {@code
   * BUSINESS_UNIT} ("division") and {@code TEAM} levels — is deleted (one {@code OrgUnitDeleted}
   * each) so finance's and employee's cached org trees purge them instead of keeping inert refs.
   *
   * <p>Order matters: outlets are detached BEFORE their former parents are deleted, so no row is
   * ever left pointing at a row that no longer exists (org-service has no intra-schema FKs — the
   * invariant is enforced here).
   *
   * <p>Must be called inside a {@link TenantContext} scope bound to {@code companyId}.
   *
   * @param companyId the tenant being flattened (equal to the bound scope)
   * @return a short summary for the boot log — how many outlets moved and how many nodes retired
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Result flatten(UUID companyId) {
    // RLS-scoped to the bound tenant; an org tree is small, so loading it whole is cheaper than
    // several targeted queries. The entities are mutated and saved, so this is the write path and
    // the full aggregate is required (not a projection).
    List<OrgUnit> all = orgUnitRepository.findAll();

    List<OrgUnit> toRetire = new ArrayList<>();
    int moved = 0;

    for (OrgUnit unit : all) {
      if (unit.getType() == OrgUnitType.OUTLET) {
        if (unit.detachFromParent()) {
          orgUnitRepository.save(unit);
          emitChanged(unit, companyId, OrgUnitChangeKind.MOVED);
          moved++;
        }
      } else {
        // A BUSINESS_UNIT or TEAM row written before ADR 0070. Nothing in the flat model can
        // represent it, and nothing operational ever bound to it (sales, menus, tables, bills and
        // assignments all key on the OUTLET id — ADR 0012 guaranteed that).
        toRetire.add(unit);
      }
    }

    // Emit BEFORE the deletes, while the aggregates still hold their state; the outbox rows ride
    // this transaction's connection so events and deletes commit together (rule 3).
    for (OrgUnit unit : toRetire) {
      emitDeleted(unit, companyId);
    }
    orgUnitRepository.deleteAll(toRetire);
    orgUnitRepository.flush();

    return new Result(moved, toRetire.size());
  }

  private void emitChanged(OrgUnit orgUnit, UUID companyId, OrgUnitChangeKind kind) {
    GenericRecord event = OrgUnitChangedSchema.toRecord(orgUnit, kind);
    outboxWriter.write(
        OrgUnitChangedSchema.AGGREGATE_TYPE,
        orgUnit.getId().toString(),
        OrgUnitChangedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        companyId,
        clock.instant());
  }

  private void emitDeleted(OrgUnit orgUnit, UUID companyId) {
    GenericRecord event = OrgUnitDeletedSchema.toRecord(orgUnit);
    outboxWriter.write(
        OrgUnitDeletedSchema.AGGREGATE_TYPE,
        orgUnit.getId().toString(),
        OrgUnitDeletedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        companyId,
        clock.instant());
  }

  /**
   * What one tenant's flattening did.
   *
   * @param outletsMoved outlets lifted from under a division to the top level
   * @param nodesRetired {@code BUSINESS_UNIT} / {@code TEAM} rows permanently removed
   */
  public record Result(int outletsMoved, int nodesRetired) {

    /** Whether this tenant's tree was already flat (nothing to do). */
    public boolean isNoop() {
      return outletsMoved == 0 && nodesRetired == 0;
    }
  }
}
