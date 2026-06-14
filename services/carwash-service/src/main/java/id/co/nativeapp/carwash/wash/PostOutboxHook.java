package id.co.nativeapp.carwash.wash;

/**
 * A test seam invoked inside {@link WashWriter#create} <em>immediately after</em> the outbox rows
 * are written but still inside the transaction.
 *
 * <p>The production bean ({@link Noop}) does nothing, so this is invisible at runtime. The
 * atomicity test installs a hook that throws here to force a failure AFTER the outbox writes —
 * proving the wash row and the outbox rows ({@code SaleRecorded} + {@code MetricPublished}) roll
 * back together (rule 3: the outbox commits atomically with the aggregate, or not at all).
 */
@FunctionalInterface
public interface PostOutboxHook {

  /**
   * Called just after the {@code SaleRecorded} + {@code MetricPublished} outbox rows are written,
   * within the same transaction. A thrown exception rolls back both the wash and the outbox rows.
   *
   * @param wash the wash just persisted in this transaction
   */
  void afterOutboxWrite(Wash wash);

  /** The production no-op hook. */
  final class Noop implements PostOutboxHook {
    @Override
    public void afterOutboxWrite(Wash wash) {
      // intentionally nothing
    }
  }
}
