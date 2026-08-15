package id.co.nativeapp.restaurant.register.dto;

import id.co.nativeapp.restaurant.register.projection.ClosedSessionSalesView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of the manager/owner past closed-day history browse — a CLOSED register session plus its
 * headline sales figures for the list (tap-through opens the full Z-report via {@code
 * /api/v1/register-sessions/{id}/summary}). {@code netSalesMinor} equals that session's Z-report
 * net (total − refunds) over its {@code [openedAt, closedAt)} window. All money is integer minor
 * units in {@code currency} (rule 8); formatting is the client's job.
 */
public record ClosedSessionSummaryResponse(
    UUID sessionId,
    LocalDate businessDate,
    Instant openedAt,
    Instant closedAt,
    String currency,
    long netSalesMinor,
    long transactionCount) {

  public static ClosedSessionSummaryResponse from(ClosedSessionSalesView v) {
    return new ClosedSessionSummaryResponse(
        v.getId(),
        v.getBusinessDate(),
        v.getOpenedAt(),
        v.getClosedAt(),
        v.getCurrency() == null ? null : v.getCurrency().strip(),
        v.getNetSalesMinor(),
        v.getTransactionCount());
  }
}
