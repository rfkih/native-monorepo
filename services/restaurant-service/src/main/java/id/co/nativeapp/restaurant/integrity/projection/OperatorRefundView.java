package id.co.nativeapp.restaurant.integrity.projection;

/**
 * Read projection for one operator's refunds over the window.
 *
 * <p>Separate from {@link OperatorActivityView} because a refund lives on its own append-only row
 * ({@code payment_refund}, V22) with its OWN timestamp: a payment taken in March and refunded in
 * April belongs to March's activity and April's refunds. Folding both into one query would force a
 * single window onto two different events and quietly misattribute every cross-period refund.
 *
 * <p>The actor is the one who took the ORIGINAL payment, read through the join — {@code
 * payment_refund} carries no actor of its own.
 *
 * <p>Backs {@code SalesIntegrityRepository.findOperatorRefunds}.
 */
public interface OperatorRefundView {

  String getActor();

  long getRefundCount();

  long getRefundMinor();

  String getCurrency();
}
