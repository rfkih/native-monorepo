package id.co.nativeapp.finance.register.messaging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * @param overShortMinor SIGNED {@code counted − expected} cash variance
 * @param currency ISO-4217 code shared by every amount on this event
 * @param tenders non-cash per-tender reconciliation lines (CARD/QRIS/ONLINE) counted at close (ADR
 *     0038 phase 2); empty on a cash-only / pre-phase-2 close. Finance posts each element's
 *     variance truing that tender's clearing account, alongside the cash variance above
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
    String currency,
    List<TenderReconciliation> tenders) {

  /**
   * One non-cash tender's expected-vs-counted at close (ADR 0038 phase 2). {@code overShortMinor}
   * is SIGNED {@code counted − expected}; finance posts it truing the tender's clearing account.
   */
  public record TenderReconciliation(
      String tenderType, long expectedMinor, long countedMinor, long overShortMinor) {}

  /** Decodes the Avro {@link GenericRecord} into the typed event. */
  public static RegisterSessionClosedEvent from(UUID eventId, GenericRecord record) {
    List<TenderReconciliation> tenders = new ArrayList<>();
    Object raw = record.get("tenders");
    if (raw instanceof List<?> list) {
      for (Object element : list) {
        GenericRecord line = (GenericRecord) element;
        tenders.add(
            new TenderReconciliation(
                line.get("tender_type").toString(),
                (long) line.get("expected_minor"),
                (long) line.get("counted_minor"),
                (long) line.get("over_short_minor")));
      }
    }
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
        record.get("currency").toString(),
        tenders);
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
    long expected;
    try {
      expected =
          Math.subtractExact(Math.addExact(openingFloatMinor, cashSalesMinor), cashRefundsMinor);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException(
          "RegisterSessionClosed identity overflow — poison event, routing to DLT", overflow);
    }
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
    long overShort = Math.subtractExact(countedCashMinor, expectedCashMinor);
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
    // Per-tender (non-cash) reconciliation identities (ADR 0038 phase 2): counted >= 0 and
    // over_short == counted − expected, mirroring the cash guards. A violation is poison → DLT.
    for (TenderReconciliation tender : tenders) {
      // Only the non-cash tenders belong here (cash is the top-level fields). An unknown or CASH
      // tender in the array is version skew / corruption — reject at DECODE time so it DLTs
      // immediately (non-retryable) rather than burning the retry budget at posting time.
      if (!"CARD".equals(tender.tenderType())
          && !"QRIS".equals(tender.tenderType())
          && !"ONLINE".equals(tender.tenderType())) {
        throw new IllegalStateException(
            "RegisterSessionClosed unknown non-cash tender_type '"
                + tender.tenderType()
                + "' — poison event, routing to DLT");
      }
      if (tender.countedMinor() < 0) {
        throw new IllegalStateException(
            "RegisterSessionClosed tender sign guard violated: "
                + tender.tenderType()
                + " counted("
                + tender.countedMinor()
                + ") must be >= 0 — poison event, routing to DLT");
      }
      long tenderOverShort;
      try {
        tenderOverShort = Math.subtractExact(tender.countedMinor(), tender.expectedMinor());
      } catch (ArithmeticException overflow) {
        throw new IllegalStateException(
            "RegisterSessionClosed tender identity overflow ("
                + tender.tenderType()
                + ") — poison event, routing to DLT",
            overflow);
      }
      if (tenderOverShort != tender.overShortMinor()) {
        throw new IllegalStateException(
            "RegisterSessionClosed tender variance identity violated: "
                + tender.tenderType()
                + " counted("
                + tender.countedMinor()
                + ") - expected("
                + tender.expectedMinor()
                + ") = "
                + tenderOverShort
                + " != overShort("
                + tender.overShortMinor()
                + ") — poison event, routing to DLT");
      }
    }
  }
}
