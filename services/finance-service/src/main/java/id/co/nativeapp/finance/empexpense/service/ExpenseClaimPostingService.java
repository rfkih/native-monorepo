package id.co.nativeapp.finance.empexpense.service;

import id.co.nativeapp.finance.empexpense.messaging.ExpenseClaimApprovedEvent;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseClaimVoidedEvent;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseReimbursementSettledEvent;
import id.co.nativeapp.tenant.TenantContext;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates posting the three expense-claims events (ADR 0030) to the dimensional ledger + GL —
 * the expense-claims counterpart of {@code ExpensePostingService}, extended to three writers.
 *
 * <p><strong>The inbound tenant comes from the event, not a request.</strong> finance is purely
 * downstream; there is no JWT on the consumer path. Each {@code handle*} method binds the tenant
 * scope from the event's {@code company_id} via {@link TenantContext#callAs} with a fixed {@code
 * "finance-consumer"} actor (which lands in the Auditable {@code created_by}), then delegates to
 * the proxied writer bean so the {@code @Transactional} advice and the auto-RLS aspect engage under
 * that tenant.
 *
 * <p><strong>Settle-once conflict recovery ({@link #handleSettled}).</strong> A concurrent racer
 * for the SAME claim (a payroll-supersession re-emission arriving under a different event id) can
 * win the {@code employee_expense_settlement} guard's UNIQUE constraint after {@link
 * ExpenseSettlementWriter#settle}'s own fast pre-check passed, aborting that writer's transaction
 * with a {@link DataIntegrityViolationException}. This method catches it and re-checks the guard in
 * a FRESH transaction ({@link ExpenseSettlementWriter#existsByClaimIdForReplay}) — mirrors {@code
 * GiftCardSaleService}/{@code SaleService}'s conflict-recovery idiom, but resolves to a logged
 * no-op (never a double post) rather than a translated conflict, since a re-settlement is always
 * financially identical (ADR 0030 §7).
 */
@Service
public class ExpenseClaimPostingService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "finance-consumer";

  private static final Logger log = LoggerFactory.getLogger(ExpenseClaimPostingService.class);

  private final ExpenseClaimPostingWriter approvedWriter;
  private final ExpenseClaimVoidWriter voidWriter;
  private final ExpenseSettlementWriter settlementWriter;

  public ExpenseClaimPostingService(
      ExpenseClaimPostingWriter approvedWriter,
      ExpenseClaimVoidWriter voidWriter,
      ExpenseSettlementWriter settlementWriter) {
    this.approvedWriter = approvedWriter;
    this.voidWriter = voidWriter;
    this.settlementWriter = settlementWriter;
  }

  /**
   * Handles one decoded {@code ExpenseClaimApproved}, idempotently.
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer
   */
  public boolean handleApproved(ExpenseClaimApprovedEvent event) {
    return callAs(event.companyId(), () -> approvedWriter.postApproved(event));
  }

  /**
   * Handles one decoded {@code ExpenseClaimVoided}, idempotently.
   *
   * @return {@code true} if this delivery ran (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer
   */
  public boolean handleVoided(ExpenseClaimVoidedEvent event) {
    return callAs(event.companyId(), () -> voidWriter.postVoided(event));
  }

  /**
   * Handles one decoded {@code ExpenseReimbursementSettled}, idempotently AND settle-once per claim
   * (see the class javadoc's conflict-recovery note).
   *
   * @return {@code true} if this delivery ran (first delivery of this event id — which may still be
   *     a logged no-op, either the writer's own fast pre-check or this method's race recovery),
   *     {@code false} if it was a re-delivery of the same event id skipped by the idempotent
   *     consumer
   */
  public boolean handleSettled(ExpenseReimbursementSettledEvent event) {
    return callAs(
        event.companyId(),
        () -> {
          try {
            return settlementWriter.settle(event);
          } catch (DataIntegrityViolationException race) {
            boolean alreadySettled = settlementWriter.existsByClaimIdForReplay(event.claimId());
            if (alreadySettled) {
              log.info(
                  "ExpenseReimbursementSettled: lost the settle-once race for claimId={};"
                      + " recovered as a no-op (ADR 0030 §7, no amounts logged)",
                  event.claimId());
              return true;
            }
            throw race;
          }
        });
  }

  private boolean callAs(String companyId, Callable<Boolean> action) {
    try {
      return TenantContext.callAs(companyId, CONSUMER_ACTOR, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; action throws only unchecked, so this is unreachable in
      // practice — rewrap defensively.
      throw new IllegalStateException("Failed to handle expense-claim event", e);
    }
  }
}
