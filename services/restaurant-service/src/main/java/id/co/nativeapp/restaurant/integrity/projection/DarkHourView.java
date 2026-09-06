package id.co.nativeapp.restaurant.integrity.projection;

import java.time.LocalDate;

/**
 * Read projection for an hour the outlet was trading but recorded NOTHING, on a day and at an hour
 * its own history says it normally does business.
 *
 * <p>The comparison is against the outlet's own past — the median sale count for that same weekday
 * and hour over the preceding weeks — never against a fixed threshold, because a quiet Tuesday
 * 15:00 and a busy Saturday 19:00 are not comparable and a global rule would flag one or miss the
 * other. This is the signal that catches a till switched off through the lunch rush at an outlet
 * that never does a stock count.
 *
 * <p>Backs {@code SalesIntegrityRepository.findDarkHours}.
 */
public interface DarkHourView {

  LocalDate getBusinessDate();

  /** Outlet-local hour, 0-23. */
  int getHourOfDay();

  /** The median number of sales this weekday+hour normally sees, from the outlet's own history. */
  long getExpectedCount();
}
