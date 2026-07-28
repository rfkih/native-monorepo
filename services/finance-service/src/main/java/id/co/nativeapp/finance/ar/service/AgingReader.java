package id.co.nativeapp.finance.ar.service;

import id.co.nativeapp.finance.ar.dto.AgingResponse;
import id.co.nativeapp.finance.ar.dto.AgingResponse.AgingRow;
import id.co.nativeapp.finance.ar.dto.AgingResponse.AgingTotals;
import id.co.nativeapp.finance.ar.projection.AgingInvoiceView;
import id.co.nativeapp.finance.ar.repository.InvoiceRepository;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the AR aging report for the bound tenant: outstanding invoice balances grouped by customer
 * and bucketed by days-overdue against an {@code asOf} date (current / 1–30 / 31–60 / 61–90 / 90+).
 * The bucketing is done in Java over the {@link AgingInvoiceView} rows (RLS-scoped) — the SQL just
 * returns each outstanding invoice's due date + balance.
 */
@Service
public class AgingReader {

  private static final int BUCKETS = 5;
  private static final int CURRENT = 0;
  private static final int D_1_30 = 1;
  private static final int D_31_60 = 2;
  private static final int D_61_90 = 3;
  private static final int D_90_PLUS = 4;

  private final InvoiceRepository invoiceRepository;
  private final Clock clock;

  public AgingReader(InvoiceRepository invoiceRepository, Clock clock) {
    this.invoiceRepository = invoiceRepository;
    this.clock = clock;
  }

  /**
   * The aging report as of {@code asOf} (a null {@code asOf} defaults to today). Rows are ranked by
   * outstanding balance descending.
   */
  @Transactional(readOnly = true)
  public AgingResponse aging(LocalDate asOf) {
    LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now(clock);
    List<AgingInvoiceView> outstanding = invoiceRepository.findOutstanding();

    Map<UUID, long[]> byCustomer = new LinkedHashMap<>();
    Map<UUID, String> names = new LinkedHashMap<>();
    long[] totals = new long[BUCKETS];
    String currency = null;

    for (AgingInvoiceView row : outstanding) {
      String rowCurrency = row.getCurrency().strip();
      if (currency == null) {
        currency = rowCurrency;
      } else if (!currency.equals(rowCurrency)) {
        // Defense-in-depth (code-review M2): outstanding invoices are all the base currency
        // (guarded
        // at issue by M1), so mixed currencies here mean a data invariant broke — fail loudly (→
        // 422)
        // rather than sum e.g. IDR + USD minor units into a meaningless total.
        throw new MismatchedPostingCurrencyException("AR-aging", currency, rowCurrency);
      }
      UUID customerId = row.getCustomerId();
      names.putIfAbsent(customerId, row.getCustomerName());
      long[] buckets = byCustomer.computeIfAbsent(customerId, k -> new long[BUCKETS]);
      int idx = bucketIndex(row.getDueDate(), effectiveAsOf);
      buckets[idx] += row.getOutstandingMinor();
      totals[idx] += row.getOutstandingMinor();
    }

    List<AgingRow> rows =
        byCustomer.entrySet().stream()
            .map(e -> toRow(e.getKey(), names.get(e.getKey()), e.getValue()))
            .sorted(Comparator.comparingLong(AgingRow::outstandingMinor).reversed())
            .toList();

    AgingTotals totalsRow =
        new AgingTotals(
            totals[CURRENT],
            totals[D_1_30],
            totals[D_31_60],
            totals[D_61_90],
            totals[D_90_PLUS],
            sum(totals));

    return new AgingResponse(effectiveAsOf, currency, rows, totalsRow);
  }

  private static AgingRow toRow(UUID customerId, String customerName, long[] buckets) {
    return new AgingRow(
        customerId,
        customerName,
        buckets[CURRENT],
        buckets[D_1_30],
        buckets[D_31_60],
        buckets[D_61_90],
        buckets[D_90_PLUS],
        sum(buckets));
  }

  /** The days-overdue bucket for a due date vs {@code asOf} (0 = current / not yet due). */
  private static int bucketIndex(LocalDate dueDate, LocalDate asOf) {
    long daysOverdue = ChronoUnit.DAYS.between(dueDate, asOf);
    if (daysOverdue <= 0) {
      return CURRENT;
    }
    if (daysOverdue <= 30) {
      return D_1_30;
    }
    if (daysOverdue <= 60) {
      return D_31_60;
    }
    if (daysOverdue <= 90) {
      return D_61_90;
    }
    return D_90_PLUS;
  }

  private static long sum(long[] buckets) {
    long total = 0L;
    for (long value : buckets) {
      total += value;
    }
    return total;
  }
}
