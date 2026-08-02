package id.co.nativeapp.restaurant.register.dto;

import id.co.nativeapp.restaurant.register.domain.RegisterSession;
import id.co.nativeapp.restaurant.register.projection.RegisterSessionView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A register session as returned to the POS (ADR 0036). Close-only figures are null while OPEN.
 * All money is integer minor units in {@code currency} (rule 8) — formatting is the client's job.
 */
public record RegisterSessionResponse(
    UUID id,
    UUID businessId,
    String status,
    LocalDate businessDate,
    Instant openedAt,
    long openingFloatMinor,
    String currency,
    Instant closedAt,
    Long cashSalesMinor,
    Long cashRefundsMinor,
    Long expectedCashMinor,
    Long countedCashMinor,
    Long overShortMinor) {

  /** Maps the write-side aggregate (post open/close) to the response shape. */
  public static RegisterSessionResponse from(RegisterSession s) {
    return new RegisterSessionResponse(
        s.getId(),
        s.getBusinessId(),
        s.getStatus(),
        s.getBusinessDate(),
        s.getOpenedAt(),
        s.getOpeningFloatMinor(),
        s.getCurrency(),
        s.getClosedAt(),
        s.getCashSalesMinor(),
        s.getCashRefundsMinor(),
        s.getExpectedCashMinor(),
        s.getCountedCashMinor(),
        s.getOverShortMinor());
  }

  /** Maps the native read projection to the response shape (CHAR(3) currency stripped). */
  public static RegisterSessionResponse from(RegisterSessionView v) {
    return new RegisterSessionResponse(
        v.getId(),
        v.getBusinessId(),
        v.getStatus(),
        v.getBusinessDate(),
        v.getOpenedAt(),
        v.getOpeningFloatMinor(),
        v.getCurrency() == null ? null : v.getCurrency().strip(),
        v.getClosedAt(),
        v.getCashSalesMinor(),
        v.getCashRefundsMinor(),
        v.getExpectedCashMinor(),
        v.getCountedCashMinor(),
        v.getOverShortMinor());
  }
}
