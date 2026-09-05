package id.co.nativeapp.restaurant.integrity.projection;

/**
 * Read projection for the sales that were rung while NO register session was open at the outlet.
 *
 * <p>Not itself proof of a leak — but a till trading outside a session is a till whose drawer is
 * never counted against what it took, so it is the condition under which an unrecorded sale leaves
 * no trace anywhere else. One aggregate row (or none, when every sale fell inside a session).
 *
 * <p>Backs {@code SalesIntegrityRepository.findSalesOutsideAnySession}.
 */
public interface OutsideSessionSalesView {

  long getSaleCount();

  long getTotalMinor();

  String getCurrency();
}
