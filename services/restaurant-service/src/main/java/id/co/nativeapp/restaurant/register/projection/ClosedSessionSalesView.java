package id.co.nativeapp.restaurant.register.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection for the manager/owner "past closed-day sales history" browse (a CLOSED {@link
 * id.co.nativeapp.restaurant.register.domain.RegisterSession} plus the day's headline sales
 * figures) — backs {@code RegisterSessionRepository#findClosedHistoryWithSalesByBusinessId}.
 *
 * <p>{@code netSalesMinor} is computed the SAME way as the Z-report ({@code
 * RegisterSessionWriter#summarize}): Σ tendered {@code sale.amount_minor} over the session window
 * {@code [opened_at, closed_at)} MINUS Σ {@code payment_refund.amount_minor} for the
 * CASH/CARD/QRIS/ ONLINE tenders over the same window — so a row's net equals that session's
 * Z-report net. Money is integer minor units in {@code currency} (rule 8). Lives in its own {@code
 * projection} package.
 */
public interface ClosedSessionSalesView {

  UUID getId();

  LocalDate getBusinessDate();

  Instant getOpenedAt();

  Instant getClosedAt();

  String getCurrency();

  long getNetSalesMinor();

  long getTransactionCount();
}
