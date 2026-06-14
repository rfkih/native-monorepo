package id.co.nativeapp.entitlement.entitlement;

/**
 * A test seam invoked inside {@link EntitlementWriter#grant} <em>immediately after</em> the {@code
 * EntitlementGranted} outbox row is written but still inside the transaction.
 *
 * <p>The production bean ({@link Noop}) does nothing, so this is invisible at runtime. The
 * atomicity test installs a hook that throws here to force a failure AFTER the outbox write —
 * proving the {@code tenant_entitlement} row and the outbox row roll back together (rule 3: the
 * outbox commits atomically with the aggregate, or not at all). This mirrors restaurant-service's
 * identically-named seam behind {@code SaleWriter.create} / {@code RecordSaleAtomicityTest}.
 */
@FunctionalInterface
public interface PostOutboxHook {

  /**
   * Called just after the {@code EntitlementGranted} outbox row is written, within the same
   * transaction. A thrown exception rolls back both the entitlement row and the outbox row.
   *
   * @param entitlement the entitlement just persisted in this transaction
   */
  void afterOutboxWrite(TenantEntitlement entitlement);

  /** The production no-op hook. */
  final class Noop implements PostOutboxHook {
    @Override
    public void afterOutboxWrite(TenantEntitlement entitlement) {
      // intentionally nothing
    }
  }
}
