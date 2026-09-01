package id.co.nativeapp.org.company.service;

import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Drains the ADR 0070 org-tree flattening queue once, at boot: for every tenant the {@code V15}
 * migration queued, it lifts the outlets to the top level and retires the {@code BUSINESS_UNIT}
 * ("division") and {@code TEAM} rows, publishing the corresponding events through the outbox.
 *
 * <p><strong>Why this is not plain SQL.</strong> Reparenting and retiring nodes are STATE CHANGES,
 * and every state change publishes its event through the transactional outbox (rule 3) so finance's
 * {@code org_unit_ref} and employee's {@code org_unit_projection} converge instead of keeping rows
 * for a shape that no longer exists. Hand-serialising Avro inside a {@code .sql} file would be
 * neither maintainable nor testable.
 *
 * <p><strong>Why a queue table.</strong> {@code company} and {@code org_unit} are FORCE ROW LEVEL
 * SECURITY, and this runs at boot with no tenant bound — so it cannot enumerate the affected
 * tenants itself (every row would be filtered). Flyway can see everything, so {@code V15} does the
 * discovery into {@code org_tree_flattening_work}, which is deliberately NOT under RLS (the same
 * posture as {@code outbox}). This class reads that queue, then binds a {@link TenantContext} scope
 * per company so all the real work runs RLS-scoped through {@link OrgTreeFlatteningWriter}.
 *
 * <p><strong>Failure isolation.</strong> Each tenant is its own transaction. A tenant that throws
 * is logged and left PENDING — the next boot retries it — while the remaining tenants still
 * complete. A tenant is marked done only after its transaction commits, so a crash mid-run can at
 * worst repeat work that is idempotent anyway (the writer's work is defined by what is still
 * wrong).
 *
 * <p><strong>One instance at a time.</strong> A rolling deploy runs several replicas concurrently,
 * and the queue read is not itself a claim — so the drain is serialised behind a Postgres advisory
 * lock. Without it two replicas would flatten the same tenant and emit duplicate events with
 * DIFFERENT event ids, which the consumers' {@code ProcessedEventStore} dedupe (keyed on the event
 * id) would not catch.
 *
 * <p>Self-retiring: once every queued row is done this listener finds an empty queue and returns
 * immediately on every subsequent boot.
 */
@Component
public class OrgTreeFlatteningReconciler {

  private static final Logger log = LoggerFactory.getLogger(OrgTreeFlatteningReconciler.class);

  /**
   * Advisory-lock key for the queue drain. An arbitrary but STABLE constant — the value means
   * nothing, only that every replica of this service picks the same one. Session-scoped ({@code
   * pg_try_advisory_lock}), released in a finally, and auto-released if the connection dies, so a
   * crashed instance cannot wedge the migration.
   */
  private static final long DRAIN_LOCK_KEY = 70_0070L;

  private final JdbcTemplate jdbcTemplate;
  private final OrgTreeFlatteningWriter writer;

  public OrgTreeFlatteningReconciler(JdbcTemplate jdbcTemplate, OrgTreeFlatteningWriter writer) {
    this.jdbcTemplate = jdbcTemplate;
    this.writer = writer;
  }

  /**
   * Runs after the context is ready (so Flyway has applied {@code V15} and the datasource is live).
   * Never throws: a reconciliation failure must not stop the service from serving — the queue row
   * stays pending and the next boot retries.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    // ONE instance flattens. A rolling deploy runs several replicas at once, and two of them
    // draining the same queue would flatten the same tenant twice — emitting duplicate
    // OrgUnitChanged/OrgUnitDeleted with DIFFERENT event ids, which ProcessedEventStore cannot
    // dedupe (it keys on the event id, not the aggregate), on top of racing each other's deletes.
    // A session-level advisory lock is the cheapest correct guard: whoever gets it does the work,
    // everyone else skips this boot and finds an empty queue on the next one.
    Boolean acquired =
        jdbcTemplate.queryForObject(
            "SELECT pg_try_advisory_lock(?)", Boolean.class, DRAIN_LOCK_KEY);
    if (!Boolean.TRUE.equals(acquired)) {
      log.info("org-tree flattening: another instance holds the drain lock; skipping this boot");
      return;
    }
    try {
      drainQueue();
    } finally {
      jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, DRAIN_LOCK_KEY);
    }
  }

  /** The drain itself, run by whichever instance holds {@link #DRAIN_LOCK_KEY}. */
  private void drainQueue() {
    List<String> pending;
    try {
      pending = findPending();
    } catch (RuntimeException e) {
      log.error("org-tree flattening: could not read the work queue; skipping this boot", e);
      return;
    }
    if (pending.isEmpty()) {
      return;
    }

    log.info("org-tree flattening (ADR 0070): {} tenant(s) queued", pending.size());
    int flattened = 0;
    for (String tenant : pending) {
      UUID companyId;
      try {
        companyId = UUID.fromString(tenant);
      } catch (IllegalArgumentException notAUuid) {
        // company_id is VARCHAR(64), so a non-canonical tenant id is representable. Such a tenant
        // could never carry outbox rows (outbox.company_id IS uuid), so there is nothing this
        // reconciler could publish for it — mark it done rather than retrying every boot forever.
        log.warn("org-tree flattening: tenant id {} is not a UUID; skipping permanently", tenant);
        markDone(tenant);
        continue;
      }
      try {
        OrgTreeFlatteningWriter.Result result =
            TenantContext.callAs(
                tenant, OrgTreeFlatteningWriter.RECONCILER_ACTOR, () -> writer.flatten(companyId));
        markDone(tenant);
        flattened++;
        if (result.isNoop()) {
          log.info("org-tree flattening: tenant {} was already flat", companyId);
        } else {
          log.info(
              "org-tree flattening: tenant {} — {} outlet(s) moved to top level, {} division/team"
                  + " node(s) retired",
              companyId,
              result.outletsMoved(),
              result.nodesRetired());
        }
      } catch (Exception e) {
        // Left PENDING deliberately: the next boot retries this tenant. Other tenants continue.
        log.error("org-tree flattening: tenant {} FAILED and stays queued", tenant, e);
      }
    }
    log.info("org-tree flattening: {}/{} tenant(s) done", flattened, pending.size());
  }

  /** The queued, not-yet-processed tenants, oldest first. Names its columns (never {@code *}). */
  private List<String> findPending() {
    return jdbcTemplate.queryForList(
        "SELECT company_id FROM org_tree_flattening_work WHERE done_at IS NULL ORDER BY queued_at",
        String.class);
  }

  /**
   * Marks one tenant processed. Runs in its own implicit transaction AFTER the tenant's flattening
   * has committed, so the queue can only ever be optimistic in the safe direction: a crash between
   * the two leaves the row pending and repeats idempotent work, never the reverse.
   */
  private void markDone(String tenant) {
    jdbcTemplate.update(
        "UPDATE org_tree_flattening_work SET done_at = now() WHERE company_id = ?", tenant);
  }
}
