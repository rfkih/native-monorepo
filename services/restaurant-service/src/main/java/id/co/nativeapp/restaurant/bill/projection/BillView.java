package id.co.nativeapp.restaurant.bill.projection;

import java.util.UUID;

/**
 * Read projection over the {@code bill} row — only the columns a list/summary response needs,
 * never the {@link id.co.nativeapp.tenant.Auditable Auditable} bookkeeping.
 *
 * <p>Backs the native read queries on {@link
 * id.co.nativeapp.restaurant.bill.repository.BillRepository}. Lives in the {@code projection}
 * sub-package (CODE-STRUCTURE §3.3).
 */
public interface BillView {

  UUID getId();

  UUID getBusinessId();

  UUID getTableId();

  String getGuestLabel();

  String getStatus();

  String getCurrency();

  Long getDiscountMinor();

  UUID getSaleId();
}
