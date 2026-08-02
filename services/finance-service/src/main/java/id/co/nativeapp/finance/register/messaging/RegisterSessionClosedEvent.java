package id.co.nativeapp.finance.register.messaging;

import java.time.Instant;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;

/**
 * Decoded {@code RegisterSessionClosed} event (ADR 0036, closing kasir) — the finance-side view of
 * a closed cash-register session. Finance posts ONLY the signed variance ({@link #overShortMinor}):
 * negative = short → {@code Dr CASH_SHORT_EXPENSE / Cr CASH_CLEARING}; positive = over → {@code Dr
 * CASH_CLEARING / Cr CASH_OVER_INCOME}; zero = no journal entry (still marked processed).
 *
 * <p>The event is TRUSTED service data (like {@code SaleRecorded.amount}) — finance cannot
 * recompute the per-session cash sum (CASH_CLEARING is company-wide) — but it is NOT trusted
 * blindly: {@link #assertReconciliationIdentity()} re-derives both identities the producer claims
 * ({@code expected == float + sales − refunds} and {@code over_short == counted − expected}) plus
 * the sign guards, and a violation is a poison message the listener routes to the DLT (the {@code
 * SaleRecordedEvent.assertReconciliationIdentity} precedent).
 *
 * @param eventId the source event UUID (idempotency key — the outbox row UUID from the Kafka {@code
 *     id} header)
 * @param sessionId the cash_register_session aggregate id
 * @param companyId the owning tenant (UUID as string)
 * @param businessId the outlet whose drawer was counted
 * @param openedAt session open instant (window start)
 * @param closedAt session close instant — drives the accounting period ({@code periodOf(closedAt)})
 * @param openingFloatMinor change fund at open, minor units (≥ 0); never part of the GL trueing
 * @param cashSalesMinor Σ CASH-tender sale amounts in the window, minor units
 * @param cashRefundsMinor Σ CASH refunds paid from the drawer in the window, minor units (≥ 0)
 * @param expectedCashMinor producer-computed {@code float + sales − refunds}
 * @param countedCashMinor the cashier's physical whole-drawer count, minor units (≥ 0)
 * @param overShortMinor SIGNED {@code counted − expected}; the ONLY amount finance posts
 * @param currency ISO-4217 code shared by every amount on this event
 */
public record RegisterSessionClosedEvent(
    UUID eventId,
    UUID sessionId,
    String companyId,
    UUID businessId,
    Instant openedAt,
    Instant closedAt,
    long openingFloatMinor,
    long cashSalesMinor,
    long cashRefundsMinor,
    long expectedCashMinor,
    long countedCashMinor,
    long overShortMinor,
    String currency) {

  /** Decodes the Avro {@link GenericRecord} into the typed event. */
  public static RegisterSessionClosedEvent from(UUID eventId, GenericRecord record) {
    return new RegisterSessionClosedEvent(
        eventId,
        UUID.fromString(record.get("session_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        Instant.ofEpochMilli((long) record.get("opened_at")),
        Instant.ofEpochMilli((long) record.get("closed_at")),
        (long) record.get("opening_float_minor"),
        (long) record.get("cash_sales_minor"),
        (long) record.get("cash_refunds_minor"),
        (long) record.get("expected_cash_minor"),
        (long) record.get("counted_cash_minor"),
        (long) record.get("over_short_minor"),
        record.get("currency").toString());
  }

  /**
   * Re-asserts the producer's reconciliation identities and sign guards. A violation means the
   * event is internally inconsistent — a poison message: the listener routes it to the DLT rather
   * than posting money from contradictory figures.
   *
   * @throws IllegalStateException if any identity or sign guard is violated
   */
  public void assertReconciliationIdentity() {
    if (openingFloatMinor < 0 || cashRefundsMinor < 0 || countedCashMinor < 0) {
      throw new IllegalStateException(
          "RegisterSessionClosed sign guard violated: openingFloat("
              + openingFloatMinor
              + "), cashRefunds("
              + cashRefundsMinor
              + "), countedCash("
              + countedCashMinor
              + ") must all be >= 0 — poison event, routing to DLT");
    }
    long expected = openingFloatMinor + cashSalesMinor - cashRefundsMinor;
    if (expected != expectedCashMinor) {
      throw new IllegalStateException(
          "RegisterSessionClosed reconciliation identity violated: float("
              + openingFloatMinor
              + ") + cashSales("
              + cashSalesMinor
              + ") - cashRefunds("
              + cashRefundsMinor
              + ") = "
              + expected
              + " != expectedCash("
              + expectedCashMinor
              + ") — poison event, routing to DLT");
    }
    long overShort = countedCashMinor - expectedCashMinor;
    if (overShort != overShortMinor) {
      throw new IllegalStateException(
          "RegisterSessionClosed variance identity violated: counted("
              + countedCashMinor
              + ") - expected("
              + expectedCashMinor
              + ") = "
              + overShort
              + " != overShort("
              + overShortMinor
              + ") — poison event, routing to DLT");
    }
    if (!closedAt.isAfter(openedAt)) {
      throw new IllegalStateException(
          "RegisterSessionClosed window violated: closed_at "
              + closedAt
              + " must be after opened_at "
              + openedAt
              + " — poison event, routing to DLT");
    }
  }
}
