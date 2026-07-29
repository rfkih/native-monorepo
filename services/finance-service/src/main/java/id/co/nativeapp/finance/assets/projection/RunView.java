package id.co.nativeapp.finance.assets.projection;

import java.time.Instant;
import java.util.UUID;

/** Read projection over one amortization-run history row (Phase 6). */
public interface RunView {

  UUID getId();

  String getPeriod();

  int getLineCount();

  long getTotalMinor();

  Instant getRunAt();
}
