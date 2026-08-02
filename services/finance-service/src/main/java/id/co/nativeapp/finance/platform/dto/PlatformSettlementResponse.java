package id.co.nativeapp.finance.platform.dto;

import id.co.nativeapp.finance.platform.domain.PlatformSettlement;
import java.time.Instant;
import java.util.UUID;

/** One recorded platform settlement (ADR 0036 Phase C). */
public record PlatformSettlementResponse(
    UUID id,
    String channelCode,
    long grossMinor,
    long netMinor,
    long feeMinor,
    String currency,
    Instant settledAt) {

  /** Maps the write-side aggregate (post settle / replay) to the response shape. */
  public static PlatformSettlementResponse from(PlatformSettlement s) {
    return new PlatformSettlementResponse(
        s.getId(),
        s.getChannelCode(),
        s.getGrossMinor(),
        s.getNetMinor(),
        s.getFeeMinor(),
        s.getCurrency() == null ? null : s.getCurrency().strip(),
        s.getSettledAt());
  }
}
