package id.co.nativeapp.barbershop.ticket.service;

import id.co.nativeapp.barbershop.metric.domain.BarbershopMetricContract;
import id.co.nativeapp.barbershop.metric.domain.BarbershopMetricContract.MetricDeclaration;
import id.co.nativeapp.barbershop.metric.domain.MetricGrain;
import id.co.nativeapp.barbershop.metric.messaging.MetricPublishedSchema;
import id.co.nativeapp.barbershop.pricing.domain.PriceBreakdown;
import id.co.nativeapp.barbershop.ticket.domain.BarbershopTicket;
import id.co.nativeapp.barbershop.ticket.messaging.TicketSaleRecordedSchema;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;

/**
 * Writes the {@code SaleRecorded} + {@code MetricPublished} outbox rows for revenue recognition on
 * a barbershop ticket — shared by {@link TicketWriter} (CASH, recognised at checkout) and {@link
 * TicketCaptureWriter} (digital tenders, recognised at capture) so the two revenue-recognition call
 * sites cannot diverge (mirrors carwash-service's {@code TicketEventEmitter}, ADR 0023 decision 2).
 *
 * <p>Not itself {@code @Transactional} — always invoked from inside the caller's {@code
 * REQUIRES_NEW} transaction (which already has the tenant GUC set by the RLS auto-apply aspect on
 * the enclosing transactional bean), exactly like {@code pricing.service.TaxChargeService}.
 *
 * <p><strong>Metrics emitted (ADR 0024).</strong> Outlet-grain {@code service_count} (1) —
 * unconditional; and employee-grain {@code sales_amount} (the ticket's grand total) — CONDITIONAL
 * on a non-null barber link, subject = the barber's employee id (not the cashier, deliberately
 * unlike restaurant). Unlike carwash, there is NO {@code upsell_amount} metric — barbershop declares
 * only one unconditional outlet-grain metric (see {@link BarbershopMetricContract}).
 *
 * <p><strong>Outbox aggregate id.</strong> The outlet-grain metric keys the outbox row's {@code
 * aggregate_id} to the OUTLET; the employee-grain metric and the {@code SaleRecorded} row key it to
 * the TICKET id (mirroring restaurant's {@code SaleWriter}, which keys {@code sales_amount@employee}
 * to the sale id).
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
   * plus (when {@code barberEmployeeId} is non-null) the {@code sales_amount@employee} metric — all
   * in the caller's active transaction (rule 3).
   *
   * @param ticket the ticket revenue is being recognised for
   * @param breakdown the resolved price breakdown
   * @param barberEmployeeId the ticket's barber employee link snapshot, or {@code null} if unlinked
   * @param companyId the owning tenant
   * @param tenderTypeName the tender enum name ({@code "CASH"}, {@code "QRIS"}, {@code "CARD"})
   * @param occurredAt when revenue was recognised (checkout time for CASH, capture time for
   *     digital)
   */
  public void recognizeRevenue(
      BarbershopTicket ticket,
      PriceBreakdown breakdown,
      UUID barberEmployeeId,
      String companyId,
      String tenderTypeName,
      Instant occurredAt) {
    writeSaleRecorded(ticket, breakdown, companyId, tenderTypeName, occurredAt);
    writeMetrics(ticket, breakdown, barberEmployeeId, companyId, occurredAt);
  }

  private void writeSaleRecorded(
      BarbershopTicket ticket,
      PriceBreakdown breakdown,
      String companyId,
      String tenderTypeName,
      Instant occurredAt) {
    GenericRecord event =
        TicketSaleRecordedSchema.toRecord(
            ticket.getId(),
            companyId,
            ticket.getBusinessId(),
            breakdown.grandTotal(),
            occurredAt,
            tenderTypeName,
            breakdown);
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
      BarbershopTicket ticket,
      PriceBreakdown breakdown,
      UUID barberEmployeeId,
      String companyId,
      Instant occurredAt) {
    String period = LocalDate.ofInstant(occurredAt, ZoneOffset.UTC).format(PERIOD);
    String outletId = ticket.getBusinessId().toString();

    for (MetricDeclaration declaration : BarbershopMetricContract.DECLARATIONS) {
      long value =
          switch (declaration.metricKey()) {
            case BarbershopMetricContract.SERVICE_COUNT -> 1L;
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

    if (barberEmployeeId != null) {
      GenericRecord event =
          MetricPublishedSchema.toRecord(
              BarbershopMetricContract.SALES_AMOUNT,
              period,
              MetricGrain.EMPLOYEE.wireValue(),
              barberEmployeeId.toString(),
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
