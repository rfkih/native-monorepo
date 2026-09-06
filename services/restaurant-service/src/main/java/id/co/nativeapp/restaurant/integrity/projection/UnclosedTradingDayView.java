package id.co.nativeapp.restaurant.integrity.projection;

import java.time.LocalDate;

/**
 * Read projection for a day the outlet traded but never closed the register — no Z-report, so
 * nothing ever reconciled the drawer against what the system says it took.
 *
 * <p>Backs {@code SalesIntegrityRepository.findTradingDaysWithoutClose}. The business date is the
 * OUTLET-LOCAL calendar day (Asia/Jakarta), matching the register's own {@code business_date}
 * convention — comparing a UTC date against it would misattribute every sale rung after 17:00 UTC.
 */
public interface UnclosedTradingDayView {

  LocalDate getBusinessDate();

  long getSaleCount();

  long getTotalMinor();

  String getCurrency();
}
