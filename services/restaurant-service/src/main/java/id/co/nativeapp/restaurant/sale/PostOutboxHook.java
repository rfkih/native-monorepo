package id.co.nativeapp.restaurant.sale;

/**
 * A test seam invoked inside {@link SaleWriter#create} <em>immediately after</em> the outbox row is
 * written but still inside the transaction.
 *
 * <p>The production bean ({@link Noop}) does nothing, so this is invisible at runtime. The
 * atomicity test installs a hook that throws here to force a failure AFTER the outbox write —
 * proving the sale row and the outbox row roll back together (rule 3: the outbox commits atomically
 * with the aggregate, or not at all).
 */
@FunctionalInterface
public interface PostOutboxHook {

  /**
   * Called just after the {@code SaleRecorded} outbox row is written, within the same transaction.
   * A thrown exception rolls back both the sale and the outbox row.
   *
   * @param sale the sale just persisted in this transaction
   */
  void afterOutboxWrite(Sale sale);

  /** The production no-op hook. */
  final class Noop implements PostOutboxHook {
    @Override
    public void afterOutboxWrite(Sale sale) {
      // intentionally nothing
    }
  }
}
