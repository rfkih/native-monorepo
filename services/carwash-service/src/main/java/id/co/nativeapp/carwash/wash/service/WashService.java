package id.co.nativeapp.carwash.wash.service;

import id.co.nativeapp.carwash.config.CarwashProperties;
import id.co.nativeapp.carwash.wash.domain.NotEntitledException;
import id.co.nativeapp.carwash.wash.domain.Wash;
import id.co.nativeapp.carwash.wash.dto.RecordWashCommand;
import id.co.nativeapp.carwash.wash.dto.RecordWashResult;
import id.co.nativeapp.entitlementcheck.EntitlementChecker;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Records washes and emits {@code SaleRecorded} + {@code MetricPublished} — the carwash vertical
 * core — GATED by the carwash entitlement.
 *
 * <p><strong>Entitlement gate (Phase-2 "real gating in the verticals").</strong> Before any side
 * effect, {@link #recordWash} consults the shared {@link EntitlementChecker} for the bound tenant
 * and the configured {@code carwash} module. The answer is served from the Redis cache, falling
 * back to the DB-backed loader over this service's LOCAL entitlement projection (kept current by
 * the {@code EntitlementGranted}/{@code EntitlementRevoked} consumer). If the company is NOT
 * entitled, it throws {@link NotEntitledException} → {@code 403}, and NO wash row is written and NO
 * event is emitted.
 *
 * <p>The actual transactional unit of work lives in {@link WashWriter}; this service orchestrates
 * it and owns the <em>concurrency-safe idempotency</em> contract: exactly one {@code SaleRecorded}
 * + one set of {@code MetricPublished} per {@code (company_id, idempotency_key)}, and a successful
 * idempotent result for <em>every</em> caller (the first creates, every other reads the existing
 * wash back) — never an unhandled {@code 500}.
 *
 * <p><strong>Why the create is a separate transaction from the conflict re-read.</strong> Two
 * concurrent record-wash calls with the same key can both pass the idempotency short-circuit and
 * then race to {@code INSERT}; the loser trips the {@code (company_id, idempotency_key)} unique
 * constraint, which raises a {@link DataIntegrityViolationException}. In PostgreSQL a constraint
 * violation <em>aborts the current transaction</em>, so the loser CANNOT re-query inside the same
 * transaction. So the create runs in its own transaction ({@link WashWriter#create}); when it fails
 * with a conflict, this (non-transactional) method catches it and performs the re-read in a FRESH
 * transaction ({@link WashWriter#findExistingByKey}), returning the winner's wash with {@code
 * created=false}. No second event is written on that path.
 */
@Service
public class WashService {

  private final WashWriter writer;
  private final EntitlementChecker entitlementChecker;
  private final String moduleKey;

  public WashService(
      WashWriter writer, EntitlementChecker entitlementChecker, CarwashProperties properties) {
    this.writer = writer;
    this.entitlementChecker = entitlementChecker;
    this.moduleKey = properties.moduleKey();
  }

  /**
   * Records a wash idempotently and emits exactly one {@code SaleRecorded} + the declared {@code
   * MetricPublished} set on first record — GATED by the carwash entitlement. A retry — sequential
   * OR concurrent — with the same {@code idempotency_key} resolves to the existing wash ({@code
   * created=false}) and emits no second event.
   *
   * <p>Not {@code @Transactional} itself: it runs the create in its own transaction so a
   * concurrent-collision conflict can be recovered by a separate-transaction re-read (a failed
   * transaction is poisoned for any further query in PostgreSQL).
   *
   * @throws NotEntitledException if the bound company is not entitled to the carwash module (→ 403)
   */
  public RecordWashResult recordWash(RecordWashCommand command) {
    // Establish the tenant once, before the gate or either transaction, so a missing scope fails
    // loudly here. The actual company_id is stamped from the bound scope inside WashWriter.
    String companyId = TenantContext.require().companyId();

    // The entitlement gate (Phase-2): reject record-wash with 403 if the company is not entitled to
    // the carwash module. Checked BEFORE the write so no row/event is produced on a denied request.
    if (!entitlementChecker.isEntitled(companyId, moduleKey)) {
      throw new NotEntitledException(moduleKey);
    }

    try {
      return writer.create(command);
    } catch (DataIntegrityViolationException conflict) {
      // A concurrent racer won the (company_id, idempotency_key) unique constraint. The create
      // transaction is now aborted; re-read the winner's wash in a fresh transaction and hand the
      // loser the SAME idempotent result (created=false). No second event is written on this path.
      return writer
          .findExistingByKey(command.idempotencyKey())
          .map(wash -> new RecordWashResult(wash, false))
          .orElseThrow(() -> conflict);
    }
  }

  /**
   * All washes visible to the bound tenant. Deliberately unfiltered in code: the result set is
   * constrained solely by the auto-applied RLS policy (no {@code WHERE company_id}). This is the
   * read path the tenancy isolation test relies on to prove a wash recorded under tenant A is
   * invisible to tenant B.
   */
  public List<Wash> findAllForCurrentTenant() {
    return writer.findAllForCurrentTenant();
  }
}
