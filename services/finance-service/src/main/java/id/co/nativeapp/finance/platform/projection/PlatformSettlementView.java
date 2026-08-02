package id.co.nativeapp.finance.platform.projection;

import java.time.Instant;
import java.util.UUID;

/** Read projection for a {@code platform_settlement} history row (ADR 0036 Phase C). */
public interface PlatformSettlementView {

  UUID getId();

  String getChannelCode();

  long getGrossMinor();

  long getNetMinor();

  long getFeeMinor();

  String getCurrency();

  Instant getSettledAt();
}
