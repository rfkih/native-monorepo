package id.co.nativeapp.carwash.ticket.service;

import id.co.nativeapp.carwash.metric.domain.CarwashMetricContract;
import id.co.nativeapp.carwash.metric.domain.CarwashMetricContract.MetricDeclaration;
import id.co.nativeapp.carwash.metric.domain.MetricGrain;
import id.co.nativeapp.carwash.metric.messaging.MetricPublishedSchema;
import id.co.nativeapp.carwash.pricing.domain.PriceBreakdown;
import id.co.nativeapp.carwash.ticket.domain.CarwashTicket;
import id.co.nativeapp.carwash.ticket.messaging.TicketSaleRecordedSchema;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;

/**
 * Writes the {@code SaleRecorded} + {@code MetricPublished} outbox rows for revenue recognition on
 * a carwash ticket — shared by {@link TicketWriter} (CASH, recognised at checkout) and {@link
 * TicketCaptureWriter} (digital tenders, recognised at capture) so the two revenue-recognition call
 * sites cannot diverge (ADR 0023 decision 2).
 *
 * <p>Not itself {@code @Transactional} — always invoked from inside the caller's {@code
 * REQUIRES_NEW} transaction (which already has the tenant GUC set by the RLS auto-apply aspect on
 * the enclosing transactional bean), exactly like {@code pricing.service.TaxChargeService}.
 *
 * <p><strong>Metrics emitted (ADR 0023 decision 4).</strong> Outlet-grain {@code wash_count} (1)
 * and {@code upsell_amount} (Σ ADDON line totals) — unconditional, mirroring {@code WashWriter};
 * and employee-grain {@code sales_amount} (the ticket's grand total) — CONDITIONAL on a non-null
 * washer link, subject = the washer's employee id (not the cashier, deliberately unlike
 * restaurant).
 *
 * <p><strong>Outbox aggregate id.</strong> The outlet-grain metrics key the outbox row's {@code
 * aggregate_id} to the OUTLET (matching {@code WashWriter}); the employee-grain metric and the
 * {@code SaleRecorded} row key it to the TICKET id (mirroring restaurant's {@code SaleWriter},
 * which keys {@code sales_amount@employee} to the sale id).
 */
@Component
public class TicketEventEmitter {

  private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private final OutboxWriter outboxWriter;

  public TicketEventEmitter(OutboxWriter outboxWriter) {
    this.outboxWriter = outboxWriter;
  }

  /**
   * Writes ONE {@code SaleRecorded} (full breakdown) plus the declared outlet-grain metric set,
   * plus (when {@code washerEmployeeId} is non-null) the {@code sales_amount@employee} metric — all
   * in the caller's active transaction (rule 3).
   *
   * @param ticket the ticket revenue is being recognised for
   * @param breakdown the resolved price breakdown
   * @param washerEmployeeId the ticket's washer employee link snapshot, or {@code null} if unlinked
   * @param addonTotal the sum of this ticket's ADDON line totals (the {@code upsell_amount} value)
   * @param companyId the owning tenant
   * @param tenderTypeName the tender enum name ({@code "CASH"}, {@code "QRIS"}, {@code "CARD"})
   * @param occurredAt when revenue was recognised (checkout time for CASH, capture time for
   *     digital)
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public void recognizeRevenue(
      CarwashTicket ticket,
      PriceBreakdown breakdown,
      UUID washerEmployeeId,
      Money addonTotal,
      String companyId,
      String tenderTypeName,
      Instant occurredAt) {
    writeSaleRecorded(ticket, breakdown, companyId, tenderTypeName, occurredAt);
    writeMetrics(ticket, breakdown, washerEmployeeId, addonTotal, companyId, occurredAt);
  }

  private void writeSaleRecorded(
      CarwashTicket ticket,
      PriceBreakdown breakdown,
      String companyId,
      String tenderTypeName,
      Instant occurredAt) {
    // Phase 4 (ADR 0027): the ticket's already-persisted (or in-memory, checkout-time) loyalty/
    // gift-card columns are the single source of truth for these fields at emission time — both
    // TicketWriter (CASH, checkout) and TicketCaptureWriter (digital, capture) pass a ticket that
    // already carries them (set at construction — CarwashTicket's redemption columns never change
    // after checkout).
    GenericRecord event =
        TicketSaleRecordedSchema.toRecord(
            ticket.getId(),
            companyId,
            ticket.getBusinessId(),
            breakdown.grandTotal(),
            occurredAt,
            tenderTypeName,
            breakdown,
            ticket.getLoyaltyMemberId(),
            ticket.getLoyaltyRedeemedPoints(),
            ticket.getLoyaltyRedeemedMinor(),
            ticket.getGiftCardId(),
            ticket.getGiftCardRedeemedMinor());
    outboxWriter.write(
        TicketSaleRecordedSchema.AGGREGATE_TYPE,
        ticket.getId().toString(),
        TicketSaleRecordedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        UUID.fromString(companyId),
        occurredAt);
  }

  private void writeMetrics(
      CarwashTicket ticket,
      PriceBreakdown breakdown,
      UUID washerEmployeeId,
      Money addonTotal,
      String companyId,
      Instant occurredAt) {
    String period = LocalDate.ofInstant(occurredAt, ZoneOffset.UTC).format(PERIOD);
    String outletId = ticket.getBusinessId().toString();

    for (MetricDeclaration declaration : CarwashMetricContract.DECLARATIONS) {
      long value =
          switch (declaration.metricKey()) {
            case CarwashMetricContract.WASH_COUNT -> 1L;
            case CarwashMetricContract.UPSELL_AMOUNT -> addonTotal.amountMinor();
            default ->
                throw new IllegalStateException(
                    "No value mapping for declared metric: " + declaration.metricKey());
          };
      GenericRecord event =
          MetricPublishedSchema.toRecord(
              declaration.metricKey(),
              period,
              declaration.grain().wireValue(),
              outletId,
              value,
              outletId);
      outboxWriter.write(
          MetricPublishedSchema.AGGREGATE_TYPE,
          outletId,
          MetricPublishedSchema.EVENT_TYPE,
          AvroSerde.serialize(event),
          null,
          UUID.fromString(companyId),
          occurredAt);
    }

    if (washerEmployeeId != null) {
      GenericRecord event =
          MetricPublishedSchema.toRecord(
              CarwashMetricContract.SALES_AMOUNT,
              period,
              MetricGrain.EMPLOYEE.wireValue(),
              washerEmployeeId.toString(),
              breakdown.grandTotal().amountMinor(),
              outletId);
      outboxWriter.write(
          MetricPublishedSchema.AGGREGATE_TYPE,
          ticket.getId().toString(),
          MetricPublishedSchema.EVENT_TYPE,
          AvroSerde.serialize(event),
          null,
          UUID.fromString(companyId),
          occurredAt);
    }
  }
}
