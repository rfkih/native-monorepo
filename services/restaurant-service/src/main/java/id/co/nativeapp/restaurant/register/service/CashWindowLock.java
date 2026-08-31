package id.co.nativeapp.restaurant.register.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The per-business (outlet) {@code pg_advisory_xact_lock} readers-writer pair that serializes
 * register-close's cash window computation against every CASH-relevant sale/refund commit for that
 * outlet (verified HIGH race, ADR 0036) — mirrors the finance {@code PlatformSettlementWriter}/
 * {@code AssetDisposalWriter} advisory-lock idiom (a {@code JdbcTemplate}-executed {@code
 * pg_advisory_xact_lock(hashtext(...))}), extended to PostgreSQL's SHARED/EXCLUSIVE advisory-lock
 * modes so sales/refunds on the SAME outlet never serialize against EACH OTHER — only against
 * close.
 *
 * <p><strong>The race this closes.</strong> {@link RegisterSessionWriter#close} sums {@code sale} /
 * {@code payment_refund} / {@code gift_card_sale} rows in {@code [openedAt, closeInstant)} via
 * plain {@code READ COMMITTED} SELECTs. A concurrent, not-yet-committed sale/refund whose eventual
 * {@code occurred_at} falls inside that window is invisible to the SUM if it has not yet committed
 * when the SELECT runs — the cash is physically in the drawer but missing from {@code expected}, a
 * phantom OVER — and because the NEXT session's window only starts at its own {@code openedAt}
 * (always {@code >= closeInstant}), that same row can never be picked up by any later close either:
 * it is lost between two windows permanently.
 *
 * <p><strong>The fix — a readers-writer lock, not a plain mutex.</strong> A naive plain-exclusive
 * lock shared by every sale/refund AND close would also serialize two UNRELATED concurrent sales on
 * the same outlet against EACH OTHER — which is wasteful (a busy outlet's checkouts would queue up
 * one-at-a-time even with no close in flight) and, worse, was PROVEN to change other transactions'
 * concurrency semantics they depend on (a loyalty/gift-card balance-decrement race test requires
 * TWO genuinely concurrent checkouts against the same cached balance). So this lock has two
 * acquisition modes on the SAME key ({@code hashtext("cash_window:" + businessId)}):
 *
 * <ul>
 *   <li>{@link #acquireForCommit} — {@code pg_advisory_xact_lock_shared} — taken by every
 *       sale/refund-COMMITTING transaction ({@code SaleWriter.create}, {@code
 *       OrderWriter.checkout}, {@code OrderWriter.payParked}, {@code BillWriter.payBill}, {@code
 *       PaymentCaptureWriter.capture}, {@code BillPaymentCaptureWriter.capture}, {@code
 *       VoidRefundWriter.refund}, {@code GiftCardSaleWriter.sell}). Any number of these may
 *       hold the SHARED lock simultaneously — they never block each other.
 *   <li>{@link #acquireForClose} — {@code pg_advisory_xact_lock} (EXCLUSIVE) — taken by {@link
 *       RegisterSessionWriter#close} (and, defensively, {@link RegisterSessionWriter#open}). An
 *       EXCLUSIVE acquisition blocks until every currently-held SHARED lock on the same key is
 *       released (i.e. every in-flight sale/refund transaction has committed or rolled back), and —
 *       while held — blocks any NEW {@link #acquireForCommit} attempt on the same key.
 * </ul>
 *
 * <p>Both modes are called as the FIRST lock-acquiring statement in the caller's transaction,
 * strictly BEFORE the timestamp that will become the row's {@code occurred_at} (a sale/refund) or
 * the close's {@code closeInstant} is captured, so exactly one of two orders happens:
 *
 * <ul>
 *   <li>A sale/refund gets the SHARED lock first: its whole transaction — including the commit —
 *       finishes before a concurrent close's EXCLUSIVE acquisition can succeed, so the close's
 *       SELECT SUM (which only runs after it holds the EXCLUSIVE lock) sees the already-committed
 *       row ({@code occurred_at} is necessarily &le; its own commit time, which is &lt; the close's
 *       {@code closeInstant}, captured only after the lock is granted).
 *   <li>Close gets the EXCLUSIVE lock first: a concurrent sale/refund's {@link #acquireForCommit}
 *       BLOCKS — before it stamps its own timestamp — until close commits and releases the
 *       EXCLUSIVE lock. Unblocked, it captures {@code Instant.now()} for real at that point, which
 *       is necessarily AFTER {@code closeInstant} — so the row correctly lands in a LATER session's
 *       window instead of neither.
 * </ul>
 *
 * <p>See {@link RegisterSessionWriter} class javadoc for the full lock contract and the list of
 * participating transactions.
 */
@Component
public class CashWindowLock {

  private final JdbcTemplate jdbcTemplate;

  public CashWindowLock(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Acquires the transaction-scoped SHARED advisory lock for {@code businessId} — for a
   * sale/refund-COMMITTING transaction. Does not block another concurrent {@link #acquireForCommit}
   * on the same business; blocks only behind a concurrent {@link #acquireForClose} (or waits for
   * one to release). Must be called FIRST — before any other lock-acquiring statement (a
   * pessimistic row lock, a guarded UPDATE, or an INSERT) — in the CALLER's {@code @Transactional}
   * method; see the class javadoc for why this ordering never introduces a cross-transaction
   * deadlock cycle in this service. Auto-released at the caller's commit or rollback.
   */
  public void acquireForCommit(UUID businessId) {
    jdbcTemplate.queryForList(
        "SELECT pg_advisory_xact_lock_shared(hashtext(?))", "cash_window:" + businessId);
  }

  /**
   * Acquires the transaction-scoped EXCLUSIVE advisory lock for {@code businessId} — for {@link
   * RegisterSessionWriter#close} (and, defensively, {@link RegisterSessionWriter#open}). Blocks
   * until every currently-held {@link #acquireForCommit} SHARED lock on the same business is
   * released, and — while held — blocks any new {@link #acquireForCommit}/{@link #acquireForClose}
   * attempt on the same business. Must be called FIRST in the CALLER's {@code @Transactional}
   * method (see class javadoc). Auto-released at the caller's commit or rollback; re-entrant within
   * the SAME transaction/session, so calling it twice for the same business in one transaction is a
   * harmless no-op the second time.
   */
  public void acquireForClose(UUID businessId) {
    jdbcTemplate.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))", "cash_window:" + businessId);
  }
}
