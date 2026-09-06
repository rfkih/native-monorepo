package id.co.nativeapp.restaurant.integrity.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for a bill that was cancelled while it still had lines on it.
 *
 * <p>An empty bill cancelled is a wrong table opened — routine, and anyone may do it. A bill
 * cancelled with items already on it is different: in a restaurant those items were plausibly
 * cooked and served, and the tab then vanished without a sale. Since the open-bill lockdown that
 * takes an owner or manager to do, which is precisely why it is worth surfacing.
 *
 * <p>The actor is {@code updated_by} — a cancel is the LAST write to the bill row, so the audit
 * column already names whoever did it.
 *
 * <p>Backs {@code SalesIntegrityRepository.findCancelledBillsWithLines}.
 */
public interface CancelledBillView {

  UUID getBillId();

  /** Who cancelled it. */
  String getActor();

  Instant getCancelledAt();

  long getLineCount();

  /** Σ of the lines that were on the bill when it was cancelled, in minor units. */
  long getTotalMinor();

  String getCurrency();
}
