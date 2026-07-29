package id.co.nativeapp.finance.ap.service;

import id.co.nativeapp.finance.ap.dto.AgingResponse;
import id.co.nativeapp.finance.ap.dto.AgingResponse.AgingRow;
import id.co.nativeapp.finance.ap.dto.AgingResponse.AgingTotals;
import id.co.nativeapp.finance.ap.projection.AgingBillView;
import id.co.nativeapp.finance.ap.repository.BillRepository;
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
 * Builds the AP aging report for the bound tenant: outstanding bill balances grouped by vendor and
 * bucketed by days-overdue against an {@code asOf} date (current / 1–30 / 31–60 / 61–90 / 90+). The
 * bucketing is done in Java over the {@link AgingBillView} rows (RLS-scoped) — the SQL just returns
 * each outstanding bill's due date + balance.
 */
@Service
public class ApAgingReader {

  private static final int BUCKETS = 5;
  private static final int CURRENT = 0;
  private static final int D_1_30 = 1;
  private static final int D_31_60 = 2;
  private static final int D_61_90 = 3;
  private static final int D_90_PLUS = 4;

  private final BillRepository billRepository;
  private final Clock clock;

  public ApAgingReader(BillRepository billRepository, Clock clock) {
    this.billRepository = billRepository;
    this.clock = clock;
  }

  /**
   * The aging report as of {@code asOf} (a null {@code asOf} defaults to today). Rows are ranked by
   * outstanding balance descending.
   */
  @Transactional(readOnly = true)
  public AgingResponse aging(LocalDate asOf) {
    LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now(clock);
    List<AgingBillView> outstanding = billRepository.findOutstanding();

    Map<UUID, long[]> byVendor = new LinkedHashMap<>();
    Map<UUID, String> names = new LinkedHashMap<>();
    long[] totals = new long[BUCKETS];
    String currency = null;

    for (AgingBillView row : outstanding) {
      String rowCurrency = row.getCurrency().strip();
      if (currency == null) {
        currency = rowCurrency;
      } else if (!currency.equals(rowCurrency)) {
        // Defense-in-depth (mirroring AR's code-review M2): outstanding bills are all the base
        // currency (guarded at post by M1), so mixed currencies here mean a data invariant broke
        // — fail loudly (→ 422) rather than sum e.g. IDR + USD minor units into a meaningless
        // total.
        throw new MismatchedPostingCurrencyException("AP-aging", currency, rowCurrency);
      }
      UUID vendorId = row.getVendorId();
      names.putIfAbsent(vendorId, row.getVendorName());
      long[] buckets = byVendor.computeIfAbsent(vendorId, k -> new long[BUCKETS]);
      int idx = bucketIndex(row.getDueDate(), effectiveAsOf);
      buckets[idx] += row.getOutstandingMinor();
      totals[idx] += row.getOutstandingMinor();
    }

    List<AgingRow> rows =
        byVendor.entrySet().stream()
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

  private static AgingRow toRow(UUID vendorId, String vendorName, long[] buckets) {
    return new AgingRow(
        vendorId,
        vendorName,
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
