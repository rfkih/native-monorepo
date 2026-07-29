package id.co.nativeapp.finance.assets.projection;

import java.util.UUID;

/**
 * Read projection over one deferral-list row (Phase 6) — the {@code deferral} columns plus the
 * amortized-to-date roll-up (Σ run-line amounts). Remaining = total − amortized, computed by the
 * reader.
 */
public interface DeferralView {

  UUID getId();

  String getKind();

  String getDescription();

  long getTotalMinor();

  int getMonths();

  String getStartPeriod();

  String getCurrency();

  long getAmortizedMinor();
}
